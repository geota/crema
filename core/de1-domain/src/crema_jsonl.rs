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

use crate::{
    Bean, BrewRecipe, CustomBrewMethod, Roaster, StoredShot, beanconqueror::ImportPlan,
    is_builtin_recipe, is_custom_method_id,
};

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
    /// User brew recipes in the bundle (built-ins are never written).
    /// Absent in bundles made before recipes were backed up.
    #[serde(default)]
    pub recipe_count: usize,
    /// User-defined brew methods in the bundle, tombstones included.
    /// Absent in bundles made before custom methods existed.
    #[serde(default)]
    pub custom_method_count: usize,
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
    /// The user's guided-brew recipes (copies and their own), `credit` /
    /// `sourceUrl` intact. Built-in recipes are bundled with the app, so a
    /// `recipe` line naming a built-in id is ignored.
    pub recipes: Vec<BrewRecipe>,
    /// Recipe organisation (`kind` stripped): `{ defaults:{method:id},
    /// hiddenBuiltins:[ids] }` — opaque to the core, like `profileMeta`.
    pub recipe_meta: Option<serde_json::Value>,
    /// The user's own brew methods (`kind:"brewMethod"` lines), tombstones
    /// included so a deleted method's past brews still resolve.
    pub custom_methods: Vec<CustomBrewMethod>,
}

/// Build a backup bundle (JSONL): `crema-backup/v1` header → settings →
/// profileMeta → recipeMeta → maintenance → visualizerPrefs → roasters →
/// beans → shots → brewMethods → recipes → profiles. Built-in recipes
/// ([`is_builtin_recipe`]) are skipped — they ship with the app. Profiles + the four config blobs ride as verbatim JSON;
/// beans/roasters/shots are typed + lossless. The shell must EXCLUDE OAuth
/// tokens, per-device/BLE state, and photos before building the envelope.
///
/// `envelope_json` = `{profiles:[…], beans:[Bean], roasters:[Roaster],
/// shots:[StoredShot], recipes:[BrewRecipe], customMethods:[CustomBrewMethod],
/// settings:{…}, profileMeta:{…},
/// recipeMeta:{…}, maintenance:{…}, visualizerPrefs:{…}}`. The last three are opaque to the core — it just
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
        recipes: Vec<BrewRecipe>,
        #[serde(default, rename = "customMethods")]
        custom_methods: Vec<CustomBrewMethod>,
        #[serde(default)]
        settings: serde_json::Value,
        #[serde(default, rename = "profileMeta")]
        profile_meta: serde_json::Value,
        #[serde(default, rename = "recipeMeta")]
        recipe_meta: serde_json::Value,
        #[serde(default)]
        maintenance: serde_json::Value,
        #[serde(default, rename = "visualizerPrefs")]
        visualizer_prefs: serde_json::Value,
    }
    let inp: In = serde_json::from_str(envelope_json).map_err(|e| e.to_string())?;
    let recipes: Vec<&BrewRecipe> = inp
        .recipes
        .iter()
        .filter(|r| !is_builtin_recipe(&r.id))
        .collect();
    let header = BackupHeader {
        kind: "crema-backup/v1".to_owned(),
        created_at: created_at_unix_ms,
        app_version: app_version.to_owned(),
        device_label: device_label.to_owned(),
        profile_count: inp.profiles.len(),
        bean_count: inp.beans.len(),
        roaster_count: inp.roasters.len(),
        shot_count: inp.shots.len(),
        recipe_count: recipes.len(),
        custom_method_count: inp.custom_methods.len(),
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
    if inp.recipe_meta.is_object() {
        push_tagged_line(&mut out, "recipeMeta", &inp.recipe_meta);
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
    // Custom methods before recipes: a restore that reads top-down meets a
    // recipe's `custom:` method after the method itself.
    for m in &inp.custom_methods {
        push_tagged_line(&mut out, "brewMethod", m);
    }
    for r in recipes {
        push_tagged_line(&mut out, "recipe", r);
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
    let mut recipe_meta = None;
    let mut shots = Vec::new();
    let mut recipes = Vec::new();
    let mut custom_methods = Vec::new();
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
            "recipeMeta" => {
                if let Some(map) = value.as_object_mut() {
                    map.remove("kind");
                }
                recipe_meta = Some(value);
            }
            "recipe" => {
                // Typed (the `kind` key is ignored as unknown); a built-in id
                // is skipped — the bundled catalogue is the source of truth.
                if let Ok(r) = serde_json::from_value::<BrewRecipe>(value)
                    && !is_builtin_recipe(&r.id)
                {
                    recipes.push(r);
                }
            }
            "brewMethod" => {
                // Typed; the `kind` key is ignored as unknown. Only the
                // `custom:` namespace is accepted — a hand-edited line can't
                // shadow a curated preset.
                if let Ok(m) = serde_json::from_value::<CustomBrewMethod>(value)
                    && is_custom_method_id(&m.id)
                {
                    custom_methods.push(m);
                }
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
        recipes,
        recipe_meta,
        custom_methods,
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
    fn backup_round_trip_keeps_the_decent_id_and_machine() {
        // Regression (#84): backup export/import parse through `StoredShot`,
        // which used to drop the web-persisted `decentId` / `machine`, so a
        // restored shot re-uploaded as a duplicate and lost its provenance.
        let envelope = serde_json::json!({
            "shots": [{
                "formatVersion": 3,
                "id": "shot:rt-2",
                "completedAt": 1_700_000_000_000_i64,
                "record": { "duration": 30_000, "samples": [] },
                "decentId": "98765",
                "machine": {
                    "serialNumber": "6262",
                    "firmwareVersion": "v1.43 build 1352",
                    "model": "DE1PRO",
                },
            }],
        });
        let jsonl =
            export_backup_jsonl_from_json(&envelope.to_string(), 1_700_000_000_000, "0.1", "Pixel")
                .unwrap();
        assert!(jsonl.contains(r#""decentId":"98765""#), "{jsonl}");
        assert!(jsonl.contains(r#""serialNumber":"6262""#), "{jsonl}");

        let plan = parse_backup_jsonl(&jsonl);
        assert_eq!(plan.shots.len(), 1);
        let shot = &plan.shots[0];
        assert_eq!(shot.decent_id.as_deref(), Some("98765"));
        assert_eq!(
            shot.machine,
            Some(crate::ShotMachine {
                serial_number: "6262".to_owned(),
                firmware_version: Some("v1.43 build 1352".to_owned()),
                model: Some("DE1PRO".to_owned()),
            })
        );
    }

    #[test]
    fn backup_writes_user_recipes_with_credits_but_never_builtins() {
        let builtin = crate::builtin_brew_recipe("builtin:hoffmann-1-cup-v60").unwrap();
        let copy = crate::duplicate_recipe(&builtin, "recipe:copy", 1_700);
        let mut own = copy.clone();
        own.id = "recipe:own".to_owned();
        own.name = "Mine".to_owned();
        own.credit = None;
        own.source_url = None;
        let envelope = serde_json::json!({
            "recipes": [builtin, copy, own],
            "recipeMeta": { "defaults": { "pourover": "recipe:copy" }, "hiddenBuiltins": ["builtin:kasuya-4-6"] },
        });
        let jsonl = export_backup_jsonl_from_json(
            &envelope.to_string(),
            1_700_000_000_000,
            "0.0.1",
            "Pixel",
        )
        .unwrap();
        assert!(!jsonl.contains("builtin:hoffmann-1-cup-v60"), "{jsonl}");
        let header: BackupHeader = serde_json::from_str(jsonl.lines().next().unwrap()).unwrap();
        assert_eq!(header.recipe_count, 2);
        assert_eq!(jsonl.matches(r#""kind":"recipe""#).count(), 2);
        let plan = parse_backup_jsonl(&jsonl);
        assert_eq!(plan.recipes, vec![copy.clone(), own]);
        let restored = &plan.recipes[0];
        assert_eq!(
            restored.credit.as_deref(),
            Some("Adapted from James Hoffmann — A Better 1 Cup V60 Technique (2022)")
        );
        assert_eq!(restored.source_url, builtin.source_url);
        let meta = plan.recipe_meta.expect("recipeMeta");
        assert_eq!(meta["defaults"]["pourover"], "recipe:copy");
        assert!(meta.get("kind").is_none());
        // A hand-edited bundle that smuggles a built-in in is ignored on read.
        let smuggled = format!(
            "{jsonl}{{\"kind\":\"recipe\",{}\n",
            &serde_json::to_string(&builtin).unwrap()[1..]
        );
        assert_eq!(parse_backup_jsonl(&smuggled).recipes.len(), 2);
        // A bundle from before recipes were backed up parses with none.
        let old = r#"{"kind":"crema-backup/v1","createdAt":1,"profileCount":0,"beanCount":0,"roasterCount":0,"shotCount":0}"#;
        let h: BackupHeader = serde_json::from_str(old).unwrap();
        assert_eq!(h.recipe_count, 0);
        assert!(parse_backup_jsonl(old).recipes.is_empty());
    }

    #[test]
    fn backup_round_trips_custom_methods_and_the_label_snapshot() {
        let orb = crate::CustomBrewMethod {
            id: "custom:01920000-0000-7000-8000-00000000abcd".to_owned(),
            label: "ORB".to_owned(),
            style: crate::BrewMethodStyle::Percolation,
            icon: Some("funnel".to_owned()),
            seed_dose_g: Some(16.0),
            seed_water_g: None,
            seed_temp_c: None,
            created_at: 1,
            updated_at: 2,
            deleted_at: None,
        };
        let mut gone = orb.clone();
        gone.id = "custom:gone".to_owned();
        gone.label = "Old brewer".to_owned();
        gone.deleted_at = Some(3);
        let shot = serde_json::json!({
            "formatVersion": 3,
            "id": "shot:orb",
            "completedAt": 1_700_000_000_000_i64,
            "record": { "duration": 0, "samples": [] },
            "brewMethod": gone.id,
            "brewMethodLabel": "Old brewer",
        });
        let recipe = crate::blank_recipe_for_style(&orb, "recipe:orb", 4);
        let envelope = serde_json::json!({
            "customMethods": [orb, gone],
            "shots": [shot],
            "recipes": [recipe],
        });
        let jsonl =
            export_backup_jsonl_from_json(&envelope.to_string(), 1, "0.0.1", "Pixel").unwrap();
        let header: BackupHeader = serde_json::from_str(jsonl.lines().next().unwrap()).unwrap();
        assert_eq!(header.custom_method_count, 2);
        assert_eq!(jsonl.matches(r#""kind":"brewMethod""#).count(), 2);
        // Methods precede the recipes that use them.
        assert!(jsonl.find("brewMethod").unwrap() < jsonl.find(r#""kind":"recipe""#).unwrap());
        let plan = parse_backup_jsonl(&jsonl);
        assert_eq!(plan.custom_methods, vec![orb.clone(), gone.clone()]);
        assert_eq!(plan.recipes[0].method, orb.id);
        assert_eq!(
            plan.shots[0].brew_method_label.as_deref(),
            Some("Old brewer")
        );
        // A line outside the custom: namespace is refused.
        let mut spoof = orb.clone();
        spoof.id = "pourover".to_owned();
        let smuggled = format!(
            "{jsonl}{{\"kind\":\"brewMethod\",{}\n",
            &serde_json::to_string(&spoof).unwrap()[1..]
        );
        assert_eq!(parse_backup_jsonl(&smuggled).custom_methods.len(), 2);
        // Older bundles: no count, no methods; a shot without a snapshot
        // omits the key entirely.
        let old = r#"{"kind":"crema-backup/v1","createdAt":1,"profileCount":0,"beanCount":0,"roasterCount":0,"shotCount":0}"#;
        let h: BackupHeader = serde_json::from_str(old).unwrap();
        assert_eq!(h.custom_method_count, 0);
        assert!(parse_backup_jsonl(old).custom_methods.is_empty());
        let mut plain = plan.shots[0].clone();
        plain.brew_method_label = None;
        assert!(
            !serde_json::to_string(&plain)
                .unwrap()
                .contains("brewMethodLabel")
        );
    }

    #[test]
    fn backup_round_trip_keeps_the_brew_log_fields() {
        // Issue #10: brew rows ride on kind:"shot" lines. A guided pourover
        // (method + recipe + weight series + water-in) and a manual log
        // (method + water-in only) both survive export → import, beside a
        // machine shot's #84 decentId / machine.
        let envelope = serde_json::json!({
            "shots": [
                {
                    "formatVersion": 3,
                    "id": "shot:brew-guided",
                    "completedAt": 1_700_000_000_000_i64,
                    "record": { "duration": 185_000, "samples": [] },
                    "metadata": { "dose": 15.0, "waterG": 250.0 },
                    "brewMethod": "pourover",
                    "recipeName": "Hoffmann V60",
                    "brewSeries": {
                        "samples": [
                            { "elapsedMs": 0, "weightG": 0.0 },
                            { "elapsedMs": 250, "weightG": 4.5, "flowGS": 18.0 },
                        ],
                        "stageMarks": [
                            { "elapsedMs": 0, "stepIndex": 0, "targetWaterG": 45.0 },
                            { "elapsedMs": 45_000, "stepIndex": 1, "targetWaterG": 250.0 },
                            // A timed wait (no target) — and the shape of an
                            // older record, written before targets existed.
                            { "elapsedMs": 90_000, "stepIndex": 2 },
                        ],
                    },
                },
                {
                    "formatVersion": 3,
                    "id": "shot:brew-manual",
                    "completedAt": 1_700_000_100_000_i64,
                    "record": { "duration": 0, "samples": [] },
                    "metadata": { "dose": 18.0, "waterG": 300.0 },
                    "brewMethod": "aeropress",
                },
                {
                    "formatVersion": 3,
                    "id": "shot:machine",
                    "completedAt": 1_700_000_200_000_i64,
                    "record": { "duration": 30_000, "samples": [] },
                    "decentId": "98765",
                    "machine": { "serialNumber": "6262" },
                },
            ],
        });
        let jsonl =
            export_backup_jsonl_from_json(&envelope.to_string(), 1_700_000_000_000, "0.1", "Pixel")
                .unwrap();
        assert!(jsonl.contains(r#""brewMethod":"pourover""#), "{jsonl}");
        assert!(jsonl.contains(r#""recipeName":"Hoffmann V60""#), "{jsonl}");
        assert!(jsonl.contains(r#""waterG":250"#), "{jsonl}");

        let plan = parse_backup_jsonl(&jsonl);
        assert_eq!(plan.shots.len(), 3);
        let by_id = |id: &str| plan.shots.iter().find(|s| s.id == id).unwrap();

        let guided = by_id("shot:brew-guided");
        assert_eq!(guided.brew_method.as_deref(), Some("pourover"));
        assert_eq!(guided.recipe_name.as_deref(), Some("Hoffmann V60"));
        assert_eq!(guided.metadata.water_g, Some(250.0));
        let series = guided.brew_series.as_ref().expect("series survives");
        assert_eq!(series.samples.len(), 2);
        assert_eq!(series.samples[1].elapsed_ms, 250);
        assert_eq!(series.samples[1].flow_g_s, Some(18.0));
        assert_eq!(series.stage_marks.len(), 3);
        // The planned targets snapshotted on the marks survive too.
        let targets: Vec<_> = series
            .stage_marks
            .iter()
            .map(|m| m.target_water_g)
            .collect();
        assert_eq!(targets, vec![Some(45.0), Some(250.0), None]);
        assert!(guided.is_brew_log() && !guided.is_manual_log());
        assert_eq!(guided.machine, None, "brews carry no machine stamp");

        let manual = by_id("shot:brew-manual");
        assert_eq!(manual.brew_method.as_deref(), Some("aeropress"));
        assert_eq!(manual.metadata.water_g, Some(300.0));
        assert_eq!(manual.recipe_name, None);
        assert_eq!(manual.brew_series, None);
        assert!(manual.is_brew_log() && manual.is_manual_log());

        let machine = by_id("shot:machine");
        assert!(!machine.is_brew_log());
        assert_eq!(machine.decent_id.as_deref(), Some("98765"));
        assert_eq!(
            machine.machine.as_ref().map(|m| m.serial_number.as_str()),
            Some("6262")
        );
    }

    #[test]
    fn a_machine_shot_serialises_without_brew_fields() {
        let envelope = serde_json::json!({
            "shots": [{
                "formatVersion": 3,
                "id": "shot:rt-4",
                "completedAt": 1_700_000_000_000_i64,
                "record": { "duration": 30_000, "samples": [] },
            }],
        });
        let jsonl =
            export_backup_jsonl_from_json(&envelope.to_string(), 1_700_000_000_000, "0.1", "Pixel")
                .unwrap();
        // The StoredShot-level brew fields are omitted outright; the
        // metadata's `waterG` follows its siblings (`nextPlan`, `tds`, …)
        // and serialises as null, which older builds ignore.
        for key in ["brewMethod", "recipeName", "brewSeries"] {
            assert!(!jsonl.contains(key), "{key} leaked: {jsonl}");
        }
        assert!(!jsonl.contains(r#""waterG":0"#), "{jsonl}");
        assert_eq!(parse_backup_jsonl(&jsonl).shots[0].metadata.water_g, None);
    }

    #[test]
    fn a_shot_without_decent_fields_serialises_without_them() {
        // Older rows / fixtures stay byte-stable: absent fields are not
        // written back as `null`.
        let envelope = serde_json::json!({
            "shots": [{
                "formatVersion": 3,
                "id": "shot:rt-3",
                "completedAt": 1_700_000_000_000_i64,
                "record": { "duration": 30_000, "samples": [] },
            }],
        });
        let jsonl =
            export_backup_jsonl_from_json(&envelope.to_string(), 1_700_000_000_000, "0.1", "Pixel")
                .unwrap();
        assert!(!jsonl.contains("decentId"), "{jsonl}");
        assert!(!jsonl.contains("\"machine\""), "{jsonl}");
        assert_eq!(parse_backup_jsonl(&jsonl).shots[0].machine, None);
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

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
    /// Rows the export left out because they couldn't be read. The counts
    /// above are of what was written, so a file with omissions still matches
    /// its own header. Skipped when 0, so a clean backup keeps its bytes.
    #[serde(default, skip_serializing_if = "is_zero")]
    pub omitted_count: usize,
}

#[allow(clippy::trivially_copy_pass_by_ref)] // serde's skip_serializing_if signature
fn is_zero(n: &usize) -> bool {
    *n == 0
}

/// One row a backup left out.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OmittedBackupRow {
    /// The row's line kind: `shot`, `bean`, `roaster`, `recipe`, `brewMethod`
    /// or `profile`.
    pub kind: &'static str,
    /// Where the row sat in the envelope's list of that kind.
    pub index: usize,
    /// The row's id, when it got far enough to have one.
    pub id: Option<String>,
}

/// What [`export_backup_jsonl_from_json`] built, and what it couldn't.
#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BackupExport {
    /// The bundle, one record per line.
    pub jsonl: String,
    /// Ids of the shots written without their `profile`, which wouldn't read.
    pub degraded: Vec<String>,
    /// The rows left out. The header's `omittedCount` is this list's length.
    pub omitted: Vec<OmittedBackupRow>,
    /// Shots written.
    pub shot_count: usize,
    /// Shots written with a curve.
    pub curve_count: usize,
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
    /// `true` when the text carries a `crema-backup/v1` header line. The parse
    /// is tolerant (a foreign or empty file gives an empty plan), so this is
    /// how a shell tells "not a Crema backup" from "an empty one".
    pub has_header: bool,
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
/// **One bad row can't block a backup.** Each row of `profiles`, `beans`,
/// `roasters`, `shots`, `recipes` and `customMethods` is read on its own, and
/// may be a JSON string holding the row: a shell that encodes rows separately
/// keeps one row's invalid JSON (Android's `NaN`) out of the others. A shot
/// that won't read is retried without its `profile`, and is reported in
/// [`BackupExport::degraded`]; any other row that won't read is left out and
/// reported in [`BackupExport::omitted`]. Everything left out counts in the
/// header's `omittedCount`, so a file made this way matches its own header.
/// A section with rows of which none could be read is a failed section, not a
/// gap: that file would look like a good backup of nothing, and retention
/// would let it replace the copies that hold those rows.
///
/// # Errors
/// Returns an error string when the envelope is malformed (not JSON, or a
/// section that isn't a list of rows) or a section with rows kept none.
pub fn export_backup_jsonl_from_json(
    envelope_json: &str,
    created_at_unix_ms: i64,
    app_version: &str,
    device_label: &str,
) -> Result<BackupExport, String> {
    #[derive(Deserialize)]
    struct In {
        #[serde(default)]
        profiles: Vec<serde_json::Value>,
        #[serde(default)]
        beans: Vec<serde_json::Value>,
        #[serde(default)]
        roasters: Vec<serde_json::Value>,
        #[serde(default)]
        shots: Vec<serde_json::Value>,
        #[serde(default)]
        recipes: Vec<serde_json::Value>,
        #[serde(default, rename = "customMethods")]
        custom_methods: Vec<serde_json::Value>,
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
    let mut omitted = Vec::new();
    let profiles = read_rows(inp.profiles, "profile", &mut omitted, read_profile_row)?;
    let beans: Vec<Bean> = read_rows(inp.beans, "bean", &mut omitted, read_row)?;
    let roasters: Vec<Roaster> = read_rows(inp.roasters, "roaster", &mut omitted, read_row)?;
    let shots: Vec<ShotRow> = read_rows(inp.shots, "shot", &mut omitted, read_shot_row)?;
    let recipes: Vec<BrewRecipe> = read_rows(inp.recipes, "recipe", &mut omitted, read_row)?;
    let custom_methods: Vec<CustomBrewMethod> =
        read_rows(inp.custom_methods, "brewMethod", &mut omitted, read_row)?;
    let recipes: Vec<&BrewRecipe> = recipes
        .iter()
        .filter(|r| !is_builtin_recipe(&r.id))
        .collect();
    let header = BackupHeader {
        kind: "crema-backup/v1".to_owned(),
        created_at: created_at_unix_ms,
        app_version: app_version.to_owned(),
        device_label: device_label.to_owned(),
        profile_count: profiles.len(),
        bean_count: beans.len(),
        roaster_count: roasters.len(),
        shot_count: shots.len(),
        recipe_count: recipes.len(),
        custom_method_count: custom_methods.len(),
        omitted_count: omitted.len(),
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
    for r in &roasters {
        push_tagged_line(&mut out, "roaster", r);
    }
    for b in &beans {
        push_tagged_line(&mut out, "bean", b);
    }
    for s in &shots {
        push_tagged_line(&mut out, "shot", &s.shot);
    }
    // Custom methods before recipes: a restore that reads top-down meets a
    // recipe's `custom:` method after the method itself.
    for m in &custom_methods {
        push_tagged_line(&mut out, "brewMethod", m);
    }
    for r in recipes {
        push_tagged_line(&mut out, "recipe", r);
    }
    for p in &profiles {
        push_tagged_line(&mut out, "profile", p);
    }
    Ok(BackupExport {
        jsonl: out,
        shot_count: shots.len(),
        curve_count: shots
            .iter()
            .filter(|s| !s.shot.record.samples.is_empty())
            .count(),
        degraded: shots
            .iter()
            .filter(|s| s.without_profile)
            .map(|s| s.shot.id.clone())
            .collect(),
        omitted,
    })
}

/// A shot read for a backup, and whether its `profile` had to go.
struct ShotRow {
    shot: StoredShot,
    without_profile: bool,
}

/// Read each row with `read`, recording the ones it can't. A row may be a JSON
/// string holding the row itself. Fails when there were rows and none could be
/// read.
fn read_rows<T>(
    rows: Vec<serde_json::Value>,
    kind: &'static str,
    omitted: &mut Vec<OmittedBackupRow>,
    read: impl Fn(&serde_json::Value) -> Option<T>,
) -> Result<Vec<T>, String> {
    let total = rows.len();
    let mut out = Vec::with_capacity(total);
    for (index, row) in rows.into_iter().enumerate() {
        let row = match row {
            serde_json::Value::String(text) => {
                serde_json::from_str(&text).unwrap_or(serde_json::Value::Null)
            }
            other => other,
        };
        match read(&row) {
            Some(value) => out.push(value),
            None => omitted.push(OmittedBackupRow {
                kind,
                index,
                id: row
                    .get("id")
                    .and_then(serde_json::Value::as_str)
                    .map(str::to_owned),
            }),
        }
    }
    if total > 0 && out.is_empty() {
        return Err(format!("none of the {total} {kind} rows could be read"));
    }
    Ok(out)
}

fn read_row<T: serde::de::DeserializeOwned>(row: &serde_json::Value) -> Option<T> {
    T::deserialize(row).ok()
}

/// A profile rides verbatim, so its row only has to be what a restore adopts:
/// an object with an id.
fn read_profile_row(row: &serde_json::Value) -> Option<serde_json::Value> {
    row.get("id")
        .and_then(serde_json::Value::as_str)
        .filter(|id| !id.is_empty())
        .map(|_| row.clone())
}

/// A shot whose `profile` won't read still has a curve and a journal worth
/// keeping, so it's retried without the `profile`, as Decent does.
fn read_shot_row(row: &serde_json::Value) -> Option<ShotRow> {
    if let Ok(shot) = StoredShot::deserialize(row) {
        return Some(ShotRow {
            shot,
            without_profile: false,
        });
    }
    let mut bare = row.clone();
    bare.as_object_mut()?.remove("profile");
    let shot = StoredShot::deserialize(&bare).ok()?;
    Some(ShotRow {
        shot,
        without_profile: true,
    })
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
    let mut has_header = false;
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
            "crema-backup/v1" => has_header = true,
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
        has_header,
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

    /// F10.7: the shot's privacy override and the bean snapshot's notes ride
    /// through a backup — the core types lacked both, so every backup
    /// dropped them.
    #[test]
    fn privacy_and_bean_notes_survive_a_backup_round_trip() {
        let mut shot = StoredShot::new(
            1_700_000_000_000,
            crate::ShotRecord {
                duration: std::time::Duration::from_secs(30),
                samples: Vec::new(),
            },
        );
        shot.privacy = Some(crate::ShotPrivacy::Private);
        shot.bean = Some(crate::ShotBean {
            name: "Gesha".into(),
            notes: Some("jasmine".into()),
            ..crate::ShotBean::default()
        });
        let envelope = serde_json::json!({ "shots": [shot] }).to_string();
        let text = export_backup_jsonl_from_json(&envelope, 1, "test", "test")
            .unwrap()
            .jsonl;
        assert!(text.contains("\"privacy\":\"private\""));
        let plan = parse_backup_jsonl(&text);
        assert_eq!(plan.shots.len(), 1);
        assert_eq!(plan.shots[0].privacy, Some(crate::ShotPrivacy::Private));
        assert_eq!(
            plan.shots[0].bean.as_ref().and_then(|b| b.notes.as_deref()),
            Some("jasmine")
        );
    }

    /// A shot row with a curve, the way a shell hands it over.
    fn shot_row(id: &str) -> serde_json::Value {
        let mut shot = StoredShot::new(
            1_700_000_000_000,
            crate::ShotRecord {
                duration: std::time::Duration::from_secs(30),
                samples: Vec::new(),
            },
        );
        shot.id = id.to_owned();
        serde_json::to_value(shot).unwrap()
    }

    fn custom_method_row() -> serde_json::Value {
        serde_json::json!({
            "id": "custom:orb", "label": "ORB", "style": "percolation",
            "createdAt": 1, "updatedAt": 2,
        })
    }

    fn header_of(jsonl: &str) -> BackupHeader {
        serde_json::from_str(jsonl.lines().next().unwrap()).unwrap()
    }

    fn export(envelope: &serde_json::Value) -> BackupExport {
        export_backup_jsonl_from_json(&envelope.to_string(), 1, "test", "test").unwrap()
    }

    /// #329: one unreadable row used to throw for the whole file.
    #[test]
    fn an_unreadable_shot_is_left_out_and_the_rest_are_backed_up() {
        let built = export(&serde_json::json!({
            "shots": [shot_row("good"), { "id": "bad", "record": "not a record" }, shot_row("also")],
        }));
        assert_eq!(
            built.omitted,
            vec![OmittedBackupRow {
                kind: "shot",
                index: 1,
                id: Some("bad".to_owned()),
            }]
        );
        let header = header_of(&built.jsonl);
        assert_eq!(header.shot_count, 2, "the header counts what was written");
        assert_eq!(header.omitted_count, 1);
        let plan = parse_backup_jsonl(&built.jsonl);
        let ids: Vec<&str> = plan.shots.iter().map(|s| s.id.as_str()).collect();
        assert_eq!(ids, ["good", "also"]);
    }

    /// As Decent does: the curve and the journal outlive a bad `profile`.
    #[test]
    fn a_shot_with_an_unreadable_profile_is_backed_up_without_it() {
        let mut row = shot_row("s1");
        row["profile"] = serde_json::json!("not a profile");
        row["profileName"] = serde_json::json!("Londinium");
        let built = export(&serde_json::json!({ "shots": [row] }));
        assert_eq!(built.degraded, ["s1"]);
        assert!(built.omitted.is_empty());
        assert_eq!(header_of(&built.jsonl).omitted_count, 0);
        let plan = parse_backup_jsonl(&built.jsonl);
        assert!(plan.shots[0].profile.is_none());
        assert_eq!(plan.shots[0].profile_name.as_deref(), Some("Londinium"));
    }

    #[test]
    fn a_shot_still_unreadable_without_its_profile_is_left_out() {
        let mut row = shot_row("s1");
        row["profile"] = serde_json::json!("not a profile");
        row["record"] = serde_json::json!(5);
        let built = export(&serde_json::json!({ "shots": [shot_row("good"), row] }));
        assert!(built.degraded.is_empty());
        assert_eq!(built.omitted.len(), 1);
        assert_eq!(header_of(&built.jsonl).shot_count, 1);
    }

    #[test]
    fn an_export_counts_the_shots_it_wrote_and_their_curves() {
        let mut curved = shot_row("curved");
        curved["record"]["samples"] = serde_json::json!([{
            "elapsed": 100,
            "sample": {
                "sampleTime": 10, "groupPressure": 9.0, "groupFlow": 2.0, "headTemp": 92.0,
                "mixTemp": 93.0, "setMixTemp": 93.0, "setHeadTemp": 92.0, "setGroupPressure": 9.0,
                "setGroupFlow": 0.0, "frameNumber": 1, "steamTemp": 140,
            },
        }]);
        let built = export(&serde_json::json!({
            "shots": [curved, shot_row("bare"), { "id": "bad", "record": "not a record" }],
        }));
        assert_eq!((built.shot_count, built.curve_count), (2, 1));
    }

    /// Every kind of row, not just shots.
    #[test]
    fn an_unreadable_row_of_any_kind_is_left_out() {
        let bad = serde_json::json!({ "id": "bad", "name": 5, "steps": 5, "createdAt": "x" });
        let recipe = crate::duplicate_recipe(
            &crate::builtin_brew_recipe("builtin:hoffmann-1-cup-v60").unwrap(),
            "recipe:copy",
            1_700,
        );
        let cases = [
            (
                "beans",
                "bean",
                serde_json::to_value(sample_bean("b1", "Good", None)).unwrap(),
            ),
            (
                "roasters",
                "roaster",
                serde_json::to_value(sample_roaster("r1", "Good")).unwrap(),
            ),
            ("recipes", "recipe", serde_json::to_value(recipe).unwrap()),
            ("customMethods", "brewMethod", custom_method_row()),
        ];
        for (key, kind, good) in cases {
            let built = export(&serde_json::json!({ key: [good, bad] }));
            assert_eq!(built.omitted.len(), 1, "{key}");
            assert_eq!(built.omitted[0].kind, kind);
            assert_eq!(built.omitted[0].id.as_deref(), Some("bad"));
            assert_eq!(header_of(&built.jsonl).omitted_count, 1);
            assert_eq!(
                built.jsonl.lines().count(),
                2,
                "the header and the good row: {key}"
            );
        }
    }

    #[test]
    fn a_profile_row_needs_an_id() {
        let built = export(&serde_json::json!({
            "profiles": [{ "id": "p1", "title": "Mine" }, { "title": "No id" }, 5],
        }));
        assert_eq!(built.omitted.len(), 2);
        assert_eq!(header_of(&built.jsonl).profile_count, 1);
        assert_eq!(parse_backup_jsonl(&built.jsonl).profiles.len(), 1);
    }

    /// Android hands rows over as strings, so one `NaN` costs one row: in a
    /// single envelope it would make the whole thing invalid JSON.
    #[test]
    fn rows_can_arrive_as_strings_and_invalid_json_costs_only_that_row() {
        let good = shot_row("good");
        let as_objects = export(&serde_json::json!({ "shots": [good] }));
        let as_strings = export(&serde_json::json!({ "shots": [good.to_string()] }));
        assert_eq!(as_strings.jsonl, as_objects.jsonl, "same bytes either way");

        let built = export(&serde_json::json!({
            "shots": [good.to_string(), r#"{"id":"nan","x":NaN}"#, serde_json::Value::Null],
        }));
        let at: Vec<(usize, Option<&str>)> = built
            .omitted
            .iter()
            .map(|o| (o.index, o.id.as_deref()))
            .collect();
        assert_eq!(
            at,
            [(1, None), (2, None)],
            "the shell looks an id up by index"
        );
        assert_eq!(parse_backup_jsonl(&built.jsonl).shots.len(), 1);
    }

    /// A clean backup keeps its bytes: the key is written only when it counts.
    #[test]
    fn a_clean_backup_has_no_omitted_count_and_an_old_header_reads_as_zero() {
        let built = export(&serde_json::json!({ "shots": [shot_row("good")] }));
        assert!(!built.jsonl.contains("omittedCount"));
        let bad = export(&serde_json::json!({ "shots": [shot_row("good"), 5] }));
        assert!(bad.jsonl.contains("\"omittedCount\":1"));
        assert_eq!(header_of(&built.jsonl).omitted_count, 0);
    }

    /// 300 unreadable shots would otherwise be a file with `shotCount: 0`: it
    /// reports success, uploads, and lets retention drop the good copies.
    #[test]
    fn a_section_with_rows_and_none_readable_fails_the_backup() {
        for key in [
            "shots",
            "beans",
            "roasters",
            "recipes",
            "customMethods",
            "profiles",
        ] {
            let envelope = serde_json::json!({ key: [5, "{ not json", serde_json::Value::Null] });
            let error = export_backup_jsonl_from_json(&envelope.to_string(), 1, "t", "t");
            assert!(error.is_err(), "{key}");
        }
    }

    /// An empty section is an empty library, not a failure.
    #[test]
    fn an_empty_section_is_not_a_failure() {
        let built = export(&serde_json::json!({ "shots": [], "beans": [] }));
        assert!(built.omitted.is_empty());
    }

    /// Dropping built-ins isn't losing rows: a list of them backs up as none.
    #[test]
    fn a_section_of_only_builtin_recipes_is_not_a_failure() {
        let builtin = crate::builtin_brew_recipe("builtin:hoffmann-1-cup-v60").unwrap();
        let built = export(&serde_json::json!({ "recipes": [builtin] }));
        assert!(built.omitted.is_empty());
        assert_eq!(header_of(&built.jsonl).recipe_count, 0);
    }

    /// A section that isn't a list is the shell's bug: no file at all, not an
    /// empty one that passes for a backup.
    #[test]
    fn a_section_that_is_not_a_list_fails_the_backup() {
        assert!(export_backup_jsonl_from_json(r#"{"shots":{}}"#, 1, "t", "t").is_err());
        assert!(export_backup_jsonl_from_json("not json", 1, "t", "t").is_err());
    }

    /// #303: what a shot took from its bag survives a backup, and a row from
    /// before the field stays `None` and writes no key.
    #[test]
    fn bag_debit_survives_a_backup_round_trip() {
        let record = || crate::ShotRecord {
            duration: std::time::Duration::from_secs(30),
            samples: Vec::new(),
        };
        let mut recorded = StoredShot::new(1_700_000_000_000, record());
        recorded.bag_debit = Some(10.5);
        let old = StoredShot::new(1_700_000_000_001, record());
        let envelope = serde_json::json!({ "shots": [recorded, old] }).to_string();
        let text = export_backup_jsonl_from_json(&envelope, 1, "test", "test")
            .unwrap()
            .jsonl;
        assert_eq!(text.matches("\"bagDebit\"").count(), 1);
        let plan = parse_backup_jsonl(&text);
        assert_eq!(plan.shots[0].bag_debit, Some(10.5));
        assert_eq!(plan.shots[1].bag_debit, None);
    }

    fn sample_bean(id: &str, name: &str, roaster_id: Option<&str>) -> Bean {
        let mut b = Bean::new(id.to_owned(), name.to_owned(), 1_700_000_000_000);
        b.roaster_id = roaster_id.map(str::to_owned);
        b
    }
    fn sample_roaster(id: &str, name: &str) -> Roaster {
        Roaster::new(id.to_owned(), name.to_owned(), 1_700_000_000_000)
    }

    /// F11b: Android's bean export (`beanLibraryJsonl`, committed as a
    /// fixture) is this format and parses in full, beans and roasters intact.
    #[test]
    fn android_bean_export_fixture_parses_in_full() {
        let plan = parse_jsonl(include_str!(
            "../tests/fixtures/beans/android-beans.crema.jsonl"
        ))
        .unwrap();
        assert_eq!(
            plan.beans.iter().map(|b| b.id.as_str()).collect::<Vec<_>>(),
            ["bean:android-1", "bean:android-2"]
        );
        assert_eq!(plan.beans[0].tags, ["comp", "washed"]);
        assert_eq!(plan.beans[0].visualizer_id.as_deref(), Some("vb-1"));
        assert_eq!(plan.roasters.len(), 1);
        assert_eq!(plan.roasters[0].name, "Onyx");
        assert!(plan.shots.is_empty());
    }

    /// F10: each shell's history export (web's and Android's
    /// `shotHistoryJsonl`, committed as fixtures) is this format, and the shot
    /// parses whole: the core's assembled record, frame 0 kept as the first
    /// frame, the bean snapshot and tags.
    #[test]
    fn web_and_android_shot_history_fixtures_parse_in_full() {
        for (shell, text) in [
            (
                "web",
                include_str!("../tests/fixtures/shots/web-shots.crema.jsonl"),
            ),
            (
                "android",
                include_str!("../tests/fixtures/shots/android-shots.crema.jsonl"),
            ),
        ] {
            let plan = parse_jsonl(text).unwrap();
            assert_eq!(plan.shots.len(), 1, "{shell}");
            let imported = &plan.shots[0];
            let shot = &imported.stored_shot;
            assert_eq!(shot.record.samples.len(), 48, "{shell}");
            assert_eq!(
                shot.record.samples[..4]
                    .iter()
                    .map(|t| t.sample.frame_number)
                    .collect::<Vec<_>>(),
                [0, 2, 2, 3],
                "{shell}"
            );
            assert_eq!(shot.grinder_model.as_deref(), Some("EK43"), "{shell}");
            assert_eq!(shot.tags, ["dial-in"], "{shell}");
            assert_eq!(imported.bean_name, "Gesha", "{shell}");
        }
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

    /// F11a: the committed backups from each shell (written by web's
    /// `buildBackupJsonl` and Android's `backupBundleJsonl`) parse in full, so
    /// both restores, which go through this parser, see every record.
    #[test]
    fn web_and_android_backup_fixtures_parse_in_full() {
        for (shell, text) in [
            (
                "web",
                include_str!("../tests/fixtures/backup/web-backup.crema.jsonl"),
            ),
            (
                "android",
                include_str!("../tests/fixtures/backup/android-backup.crema.jsonl"),
            ),
        ] {
            let plan = parse_backup_jsonl(text);
            assert!(plan.has_header, "{shell}");
            assert_eq!(
                (
                    plan.profiles.len(),
                    plan.beans.len(),
                    plan.roasters.len(),
                    plan.shots.len(),
                    plan.recipes.len(),
                    plan.custom_methods.len(),
                ),
                (1, 1, 1, 1, 1, 1),
                "{shell}"
            );
            assert!(plan.shots[0].bean.is_some(), "{shell}");
            assert_eq!(plan.shots[0].record.samples.len(), 2, "{shell}");
            assert_eq!(plan.settings.as_ref().unwrap()["_shell"], shell);
            assert!(
                plan.profile_meta.is_some() && plan.recipe_meta.is_some(),
                "{shell}"
            );
            assert!(
                plan.maintenance.is_some() && plan.visualizer_prefs.is_some(),
                "{shell}"
            );
        }
    }

    #[test]
    fn backup_plan_reports_the_header_line() {
        assert!(parse_backup_jsonl("{\"kind\":\"crema-backup/v1\"}\n").has_header);
        assert!(
            !parse_backup_jsonl("{\"kind\":\"crema/v1\"}\n{\"kind\":\"profile\",\"id\":\"p\"}")
                .has_header
        );
        assert!(!parse_backup_jsonl("").has_header);
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
                .unwrap()
                .jsonl;
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
                .unwrap()
                .jsonl;

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
                .unwrap()
                .jsonl;
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
        own.notes = Some("Sibarist Fast filter · Third Wave Water, light roast".to_owned());
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
        .unwrap()
        .jsonl;
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
        assert_eq!(
            plan.recipes[1].notes.as_deref(),
            Some("Sibarist Fast filter · Third Wave Water, light roast")
        );
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
        let jsonl = export_backup_jsonl_from_json(&envelope.to_string(), 1, "0.0.1", "Pixel")
            .unwrap()
            .jsonl;
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
                .unwrap()
                .jsonl;
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
                .unwrap()
                .jsonl;
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
                .unwrap()
                .jsonl;
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

//! Profile files: what both shells import and export (F12).
//!
//! [`import_profile_file`] reads any profile file a user is likely to hold and
//! returns library profiles ready to save; [`export_profile_file`] writes the
//! file both shells hand out. The shells only read and write the bytes.
//!
//! **Import** detects the format from the content (no filename needed):
//!
//! - one JSON object with `steps`: a community-v2 profile (de1app, reaprime,
//!   Decenza, Visualizer, web Crema);
//! - one JSON object with `segments`: a Crema library profile (Android Crema's
//!   single export before F12);
//! - one JSON array: each element by those two rules (Android's "Export all");
//! - JSON lines: a line with `kind: "profile"` is a library profile (a
//!   `crema/v1` backup); a line with any other `kind` (header, bean, shot, …)
//!   is skipped; a line without `kind` follows the object rules (web's
//!   `.jsonl` bundle);
//! - anything else: a de1app `.tcl` profile.
//!
//! Every imported profile gets a fresh core id, becomes a custom profile (the
//! `Built-in` tag dropped, not pinned, never used) and has its brew temperature
//! derived from its first step (F6). A profile of more than 20 steps is an
//! error, never truncated. Errors are per item: the rest of the file imports.
//! A broken multi-line JSON document is one error (with its line and column),
//! text that is neither JSON nor a de1app profile is "not a profile file", and
//! at most [`MAX_REPORTED_ERRORS`] errors are listed, plus a count of the rest.
//!
//! **Export** writes community v2: one profile pretty-printed, several as JSON
//! lines. The library metadata v2 has no field for (tags, roast, stop on
//! weight, auto-tare) rides in a `crema` object, which de1app, reaprime and
//! Decenza ignore and [`import_profile_file`] reads back.

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use typeshare::typeshare;

use crate::crema_profile::{CremaProfile, ProfileSource, Roast, from_wire, to_wire};
use crate::effective_profile::with_derived_brew_temp;
use crate::error::ImportError;
use crate::ids::new_profile_id;
use crate::profile_bounds::MAX_PROFILE_STEPS;
use crate::profile_import::{export_v2_json_with, import_legacy_tcl, import_v2_json};

/// The most profiles one file imports; the rest are reported as one error.
pub const MAX_PROFILES_PER_FILE: usize = 1000;

/// The tag every built-in carries; an imported copy is the user's own.
const BUILTIN_TAG: &str = "Built-in";

/// The key of Crema's extension object in an exported v2 profile.
const CREMA_KEY: &str = "crema";

/// The most errors one import reports; the rest are counted in one more.
pub const MAX_REPORTED_ERRORS: usize = 20;

/// What an import says of text that is neither JSON nor a de1app profile.
const NOT_A_PROFILE_FILE: &str = "not a profile file";

/// Keys of a de1app `.tcl` profile (`profile.tcl` `read_legacy`): a file
/// naming none of them isn't one.
const TCL_PROFILE_KEYS: [&str; 4] = [
    "profile_title",
    "settings_profile_type",
    "advanced_shot",
    "espresso_pressure",
];

/// What [`import_profile_file`] read from a file. Not shared through
/// typeshare (the shells have their own `CremaProfile` types); each shell
/// declares this shape over its own.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct ProfileFileImport {
    /// The profiles to save, in file order, each a fresh custom profile.
    pub profiles: Vec<CremaProfile>,
    /// What could not be imported, in file order.
    pub errors: Vec<ProfileFileError>,
    /// Profiles skipped as already in the library (or earlier in the file):
    /// the same title (trimmed, case-insensitive) and the same steps (see
    /// [`import_profile_file_into`]).
    #[serde(default)]
    pub skipped_duplicates: u32,
}

/// One part of a profile file that did not import.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ProfileFileError {
    /// The 1-based line (JSON lines) or element (JSON array) it came from;
    /// `None` for a single-profile file.
    pub item: Option<u32>,
    /// Why, in words a user can act on.
    pub message: String,
}

/// Read a profile file — see the module docs for the formats. Duplicates
/// within the file collapse to the first; see [`import_profile_file_into`]
/// to skip the ones already in a library too.
#[must_use]
pub fn import_profile_file(text: &str) -> ProfileFileImport {
    import_profile_file_into(text, &[])
}

/// [`import_profile_file`], skipping each profile already in `library` (the
/// user's whole library: custom profiles and built-ins, hidden ones included)
/// or earlier in the file, and counting it in `skipped_duplicates`.
///
/// The same profile means the same **title**, trimmed and compared
/// case-insensitively, and the same **steps**: everything an upload writes to
/// the DE1 (the steps and the shot header's pre-infusion count, volume limit
/// and tank temperature; ids are not part of it). A profile with the same steps under another title, or another
/// version of the steps under the same title, is a deliberate copy and is
/// imported.
#[must_use]
pub fn import_profile_file_into(text: &str, library: &[CremaProfile]) -> ProfileFileImport {
    let text = text.trim_start_matches('\u{feff}').trim();
    let mut out = Importer {
        seen: library.iter().map(duplicate_key).collect(),
        ..Importer::default()
    };
    if text.is_empty() {
        out.error(None, "the file is empty".to_owned());
        return out.finish();
    }
    if text.starts_with('{') || text.starts_with('[') {
        match serde_json::from_str::<Value>(text) {
            Ok(Value::Array(items)) => {
                for (i, item) in items.into_iter().enumerate() {
                    out.json_item(Some(index(i)), item, false);
                }
            }
            Ok(item) => out.json_item(None, item, false),
            // Not one JSON document: JSON lines — unless no line is an object
            // on its own, which makes it one broken multi-line document (a
            // pretty-printed profile with a typo): one error, with its line
            // and column.
            Err(e) if text.starts_with('{') && !some_line_is_a_json_object(text) => {
                out.error(None, format!("not valid JSON: {e}"));
            }
            Err(_) if text.starts_with('{') => {
                for (i, line) in text.lines().enumerate() {
                    let line = line.trim();
                    if line.is_empty() {
                        continue;
                    }
                    match serde_json::from_str::<Value>(line) {
                        Ok(item) => out.json_item(Some(index(i)), item, true),
                        Err(e) => out.error(Some(index(i)), format!("not valid JSON: {e}")),
                    }
                }
            }
            Err(e) => out.error(None, format!("not valid JSON: {e}")),
        }
    } else {
        match import_legacy_tcl(text) {
            Ok(wire) => out.profile(from_wire(&wire)),
            // Text that names none of de1app's profile keys is not a failed
            // TCL profile but some other file.
            Err(_) if !looks_like_tcl_profile(text) => {
                out.error(None, NOT_A_PROFILE_FILE.to_owned());
            }
            Err(e) => out.error(None, e.to_string()),
        }
    }
    out.finish()
}

/// [`import_profile_file_into`] as JSON, for the bridges: `library_json` is a
/// JSON array of the library's `CremaProfile`s (`None` = an empty library).
/// An element that isn't a `CremaProfile` is left out of the comparison, so one
/// odd stored profile can't block an import.
///
/// # Errors
///
/// A message when `library_json` is not a JSON array.
pub fn import_profile_file_json(text: &str, library_json: Option<&str>) -> Result<String, String> {
    let library: Vec<CremaProfile> = match library_json {
        Some(json) => serde_json::from_str::<Vec<serde_json::Value>>(json)
            .map_err(|e| format!("library JSON: {e}"))?
            .into_iter()
            .filter_map(|v| serde_json::from_value(v).ok())
            .collect(),
        None => Vec::new(),
    };
    serde_json::to_string(&import_profile_file_into(text, &library)).map_err(|e| e.to_string())
}

/// What makes two profiles the same for [`import_profile_file_into`]: the
/// title, trimmed and lower-cased, and what an upload puts on the DE1 — the
/// wire steps and the shot header's pre-infusion count, volume limit and tank
/// temperature. Ids (the profile's and its segments'), tags, notes and the
/// author are not part of it.
fn duplicate_key(p: &CremaProfile) -> (String, String) {
    let wire = to_wire(p);
    let machine = serde_json::json!({
        "steps": wire.steps,
        "preinfuse": wire.preinfuse_step_count,
        "volume": wire.max_total_volume_ml,
        "tank": wire.tank_temperature,
    });
    (p.name.trim().to_lowercase(), machine.to_string())
}

/// Write `profiles` as a profile file: one profile as pretty community-v2
/// JSON, two or more as JSON lines (one compact v2 profile per line, each
/// ending in `\n`). An empty list writes an empty file.
///
/// # Errors
///
/// Only if a profile fails to serialize, which a profile cannot.
pub fn export_profile_file(profiles: &[CremaProfile]) -> Result<String, serde_json::Error> {
    if let [one] = profiles {
        return export_v2_json_with(&to_wire(one), Some(extension(one)), true);
    }
    let mut out = String::new();
    for p in profiles {
        out.push_str(&export_v2_json_with(
            &to_wire(p),
            Some(extension(p)),
            false,
        )?);
        out.push('\n');
    }
    Ok(out)
}

/// [`export_profile_file`] from a JSON array of library profiles, for the
/// bridges.
///
/// # Errors
///
/// A message when `profiles_json` is not an array of library profiles.
pub fn export_profile_file_json(profiles_json: &str) -> Result<String, String> {
    let profiles: Vec<CremaProfile> =
        serde_json::from_str(profiles_json).map_err(|e| format!("profiles JSON: {e}"))?;
    export_profile_file(&profiles).map_err(|e| e.to_string())
}

/// Crema's extension object for `p`: the library metadata v2 has no field for.
fn extension(p: &CremaProfile) -> Value {
    serde_json::json!({
        "tags": p.tags,
        "roast": p.roast,
        "stopOnWeight": p.stop_on_weight,
        "autoTare": p.auto_tare,
    })
}

/// Apply an extension object read back from an exported profile. A key that
/// is missing or malformed leaves the imported value alone.
fn apply_extension(p: &mut CremaProfile, ext: &Map<String, Value>) {
    if let Some(tags) = ext
        .get("tags")
        .and_then(|v| Vec::<String>::deserialize(v).ok())
    {
        p.tags = tags;
    }
    if let Some(roast) = ext
        .get("roast")
        .and_then(|v| Option::<Roast>::deserialize(v).ok())
    {
        p.roast = roast;
    }
    if let Some(on) = ext.get("stopOnWeight").and_then(Value::as_bool) {
        p.stop_on_weight = on;
    }
    if let Some(on) = ext.get("autoTare").and_then(Value::as_bool) {
        p.auto_tare = on;
    }
}

/// Whether some line of `text` is a JSON object by itself: true of JSON lines
/// (even with a bad line), false of a pretty-printed document, whose lines
/// are fragments.
fn some_line_is_a_json_object(text: &str) -> bool {
    text.lines().any(|l| {
        matches!(
            serde_json::from_str::<Value>(l.trim()),
            Ok(Value::Object(_))
        )
    })
}

/// Whether `text` names one of the keys every de1app `.tcl` profile carries.
fn looks_like_tcl_profile(text: &str) -> bool {
    TCL_PROFILE_KEYS.iter().any(|key| {
        text.lines()
            .any(|line| line.split_whitespace().next() == Some(key))
    })
}

/// A 0-based position as the 1-based item number a user sees.
fn index(i: usize) -> u32 {
    u32::try_from(i.saturating_add(1)).unwrap_or(u32::MAX)
}

/// Collects one file's profiles and errors.
#[derive(Default)]
struct Importer {
    result: ProfileFileImport,
    /// Profiles past [`MAX_PROFILES_PER_FILE`] (reported once, at the end).
    over_cap: usize,
    /// Errors past [`MAX_REPORTED_ERRORS`] (counted once, at the end).
    more_errors: usize,
    /// The library's and this file's profiles so far ([`duplicate_key`]).
    seen: std::collections::HashSet<(String, String)>,
}

impl Importer {
    /// One JSON item: a v2 profile, a library profile, or (on a JSON line)
    /// a tagged record.
    fn json_item(&mut self, item: Option<u32>, value: Value, line: bool) {
        let Value::Object(obj) = value else {
            self.error(item, "not a profile".to_owned());
            return;
        };
        let kind = obj.get("kind").and_then(Value::as_str);
        if line && let Some(kind) = kind {
            // A backup's other records (header, beans, shots, settings) are
            // not profiles: skipped, not errors.
            if kind == "profile" {
                self.native(item, obj);
            }
            return;
        }
        if obj.contains_key("steps") {
            self.v2(item, obj);
        } else if obj.contains_key("segments") {
            self.native(item, obj);
        } else {
            self.error(
                item,
                "not a profile (it has neither steps nor segments)".to_owned(),
            );
        }
    }

    /// A community-v2 profile object.
    fn v2(&mut self, item: Option<u32>, mut obj: Map<String, Value>) {
        let ext = obj.remove(CREMA_KEY);
        let text = Value::Object(obj).to_string();
        match import_v2_json(&text) {
            Ok(wire) => {
                let mut p = from_wire(&wire);
                if let Some(Value::Object(ext)) = ext {
                    apply_extension(&mut p, &ext);
                }
                self.profile(p);
            }
            Err(e) => self.error(item, e.to_string()),
        }
    }

    /// A Crema library profile object (`kind` dropped if it came from a backup).
    fn native(&mut self, item: Option<u32>, mut obj: Map<String, Value>) {
        obj.remove("kind");
        match CremaProfile::deserialize(Value::Object(obj)) {
            Ok(p) if p.segments.is_empty() => self.error(item, ImportError::NoSteps.to_string()),
            Ok(p) if p.segments.len() > usize::from(MAX_PROFILE_STEPS) => self.error(
                item,
                ImportError::TooManySteps {
                    count: p.segments.len(),
                }
                .to_string(),
            ),
            Ok(p) => self.profile(p),
            Err(e) => self.error(item, format!("not a valid Crema profile: {e}")),
        }
    }

    /// An imported profile, made the user's own (see the module docs).
    fn profile(&mut self, mut p: CremaProfile) {
        if self.result.profiles.len() >= MAX_PROFILES_PER_FILE {
            self.over_cap += 1;
            return;
        }
        p.id = new_profile_id();
        p.source = ProfileSource::Custom;
        p.tags.retain(|t| t != BUILTIN_TAG);
        p.pinned = false;
        p.last_used = None;
        let p = with_derived_brew_temp(p);
        if !self.seen.insert(duplicate_key(&p)) {
            self.result.skipped_duplicates += 1;
            return;
        }
        self.result.profiles.push(p);
    }

    fn error(&mut self, item: Option<u32>, message: String) {
        if self.result.errors.len() < MAX_REPORTED_ERRORS {
            self.result.errors.push(ProfileFileError { item, message });
        } else {
            self.more_errors += 1;
        }
    }

    fn finish(mut self) -> ProfileFileImport {
        if self.more_errors > 0 {
            self.result.errors.push(ProfileFileError {
                item: None,
                message: format!("and {} more", self.more_errors),
            });
        }
        if self.over_cap > 0 {
            self.result.errors.push(ProfileFileError {
                item: None,
                message: format!(
                    "{} more profiles were not imported: a file holds at most {MAX_PROFILES_PER_FILE}",
                    self.over_cap
                ),
            });
        }
        self.result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::builtin_crema_profiles_json;

    const FIXTURES: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures");

    fn fixture(path: &str) -> String {
        std::fs::read_to_string(format!("{FIXTURES}/{path}"))
            .unwrap_or_else(|e| panic!("fixture {path}: {e}"))
    }

    fn titles(import: &ProfileFileImport) -> Vec<&str> {
        import.profiles.iter().map(|p| p.name.as_str()).collect()
    }

    fn builtins() -> Vec<CremaProfile> {
        serde_json::from_str(&builtin_crema_profiles_json()).unwrap()
    }

    /// `p` with what an import resets made equal, for round-trip comparison.
    fn comparable(mut p: CremaProfile) -> CremaProfile {
        p.id = String::new();
        p.source = ProfileSource::Custom;
        p.tags.retain(|t| t != BUILTIN_TAG);
        p.pinned = false;
        p.last_used = None;
        p
    }

    /// The fixed set behind the export fixtures: Android's pre-F12 "Export
    /// all" file, imported.
    fn export_set() -> Vec<CremaProfile> {
        import_profile_file(&fixture("profiles/android-profiles.json")).profiles
    }

    /// AC-F12.1: every format a user may hold imports, through one entry point.
    #[test]
    fn f12_1_every_profile_file_format_imports() {
        let cases: [(&str, &[&str]); 8] = [
            ("profiles/web-profile.json", &["7g basket"]),
            (
                "profiles/web-profiles.jsonl",
                &["7g basket", "Blooming Allongé", "A-Flow / default-dark"],
            ),
            ("profiles/android-profile.json", &["My 7g"]),
            (
                "profiles/android-profiles.json",
                &[
                    "My 7g",
                    "7g basket",
                    "Blooming Allongé",
                    "A-Flow / default-dark",
                ],
            ),
            ("profiles/crema-profile.json", &["My 7g"]),
            (
                "profiles/crema-profiles.jsonl",
                &[
                    "My 7g",
                    "7g basket",
                    "Blooming Allongé",
                    "A-Flow / default-dark",
                ],
            ),
            ("default.tcl", &["Default"]),
            ("best_practice.tcl", &["Adaptive v2"]),
        ];
        for (path, expected) in cases {
            let import = import_profile_file(&fixture(path));
            assert_eq!(import.errors, vec![], "{path}");
            assert_eq!(titles(&import), expected.to_vec(), "{path}");
        }
        // A backup's profile lines import; its other records are skipped.
        for path in [
            "backup/web-backup.crema.jsonl",
            "backup/android-backup.crema.jsonl",
        ] {
            let import = import_profile_file(&fixture(path));
            assert_eq!(import.errors, vec![], "{path}");
            assert_eq!(import.profiles.len(), 1, "{path}");
        }
        // Android's library metadata survives its own old export.
        let mine = &import_profile_file(&fixture("profiles/android-profile.json")).profiles[0];
        assert_eq!(mine.tags, vec!["Fixture", "Small"]);
        assert_eq!(mine.roast, Some(Roast::Medium));
    }

    /// AC-F12.2: every imported profile is a fresh custom profile whose brew
    /// temperature is its first step's.
    #[test]
    fn f12_2_imports_are_fresh_custom_profiles_with_a_derived_brew_temp() {
        let import = import_profile_file(&fixture("profiles/android-profiles.json"));
        let ids: std::collections::HashSet<&str> =
            import.profiles.iter().map(|p| p.id.as_str()).collect();
        assert_eq!(ids.len(), import.profiles.len(), "distinct ids");
        for p in &import.profiles {
            assert!(
                uuid::Uuid::parse_str(&p.id).is_ok_and(|u| u.get_version_num() == 7),
                "{}",
                p.id
            );
            assert_eq!(p.source, ProfileSource::Custom);
            assert!(!p.tags.iter().any(|t| t == BUILTIN_TAG), "{:?}", p.tags);
            assert!(!p.pinned && p.last_used.is_none());
        }

        // A stale top-level brew temp is ignored on a library profile…
        let mut native: Value =
            serde_json::from_str(&fixture("profiles/android-profile.json")).unwrap();
        native["brewTemp"] = Value::from(99.0);
        native["pinned"] = Value::from(true);
        native["lastUsed"] = Value::from("2026-10-01T00:00:00Z");
        let p = &import_profile_file(&native.to_string()).profiles[0];
        assert_eq!(p.brew_temp, p.segments[0].temp);
        assert!((p.brew_temp - 99.0).abs() > 1.0);
        assert!(!p.pinned && p.last_used.is_none());

        // …and on a v2 profile (Decenza writes `espresso_temperature`).
        let mut v2: Value = serde_json::from_str(&fixture("profiles/web-profile.json")).unwrap();
        v2["espresso_temperature"] = Value::from(99.0);
        let p = &import_profile_file(&v2.to_string()).profiles[0];
        assert_eq!(p.brew_temp, p.segments[0].temp);
    }

    /// AC-F12.2: a profile the DE1 can't hold (more than 20 steps) is an
    /// error, never truncated; the rest of the file still imports.
    #[test]
    fn f12_2_a_profile_over_the_step_cap_is_refused_and_the_rest_imports() {
        let mut items: Vec<Value> =
            serde_json::from_str(&fixture("profiles/android-profiles.json")).unwrap();
        let mut long = items[1].clone();
        let seg = long["segments"][0].clone();
        long["segments"] = Value::Array(vec![seg; 21]);
        items.insert(1, long);
        let import = import_profile_file(&Value::Array(items).to_string());
        assert_eq!(import.profiles.len(), 4);
        assert_eq!(import.errors.len(), 1);
        assert_eq!(import.errors[0].item, Some(2));
        assert!(
            import.errors[0].message.contains("21 steps"),
            "{}",
            import.errors[0].message
        );

        // The same through the v2 path, on a JSON line.
        let mut lines: Vec<String> = fixture("profiles/web-profiles.jsonl")
            .lines()
            .map(String::from)
            .collect();
        let mut long: Value = serde_json::from_str(&lines[0]).unwrap();
        let step = long["steps"][0].clone();
        long["steps"] = Value::Array(vec![step; 21]);
        lines[1] = long.to_string();
        let import = import_profile_file(&lines.join("\n"));
        assert_eq!(import.profiles.len(), 2);
        assert_eq!(import.errors[0].item, Some(2));
    }

    /// AC-F12.3: what is not a profile is an error, not an adopted profile.
    #[test]
    fn f12_3_what_is_not_a_profile_is_an_error() {
        // An object with a `name` but no steps or segments (Android used to
        // adopt it).
        let import = import_profile_file(r#"{"name":"Groceries","items":["milk"]}"#);
        assert_eq!(import.profiles, vec![]);
        assert!(import.errors[0].message.contains("not a profile"));
        // A library-shaped object that isn't one.
        let import = import_profile_file(r#"{"name":"x","segments":"nope"}"#);
        assert_eq!(import.profiles, vec![]);
        assert_eq!(import.errors.len(), 1);
        // Garbage and empty files.
        for text in ["hello world {", "", "   \n", "[1, 2"] {
            let import = import_profile_file(text);
            assert_eq!(import.profiles, vec![], "{text:?}");
            assert_eq!(import.errors.len(), 1, "{text:?}");
            assert!(!import.errors[0].message.is_empty());
        }
        // Plain text is not a profile file, not a profile "with no steps";
        // a broken de1app profile still gets the TCL reason.
        assert_eq!(
            import_profile_file("hello world {").errors[0].message,
            "not a profile file"
        );
        assert_eq!(
            import_profile_file("profile_title Mine\nsettings_profile_type settings_2c\n").errors
                [0]
            .message,
            ImportError::NoSteps.to_string()
        );
        // A bad JSON line names its line; the rest imports.
        let mut lines: Vec<String> = fixture("profiles/web-profiles.jsonl")
            .lines()
            .map(String::from)
            .collect();
        lines.insert(1, "{oops".to_owned());
        lines.insert(1, String::new());
        let import = import_profile_file(&lines.join("\n"));
        assert_eq!(import.profiles.len(), 3);
        assert_eq!(import.errors.len(), 1);
        assert_eq!(import.errors[0].item, Some(3), "the blank line counts");
    }

    /// AC-F12.4: the export is community v2 other apps read: pretty for one
    /// profile, JSON lines for several, with the keys reaprime requires
    /// (`profile.dart:59-71`) and de1app's `profiles_v2` metadata
    /// (`profile.tcl` `legacy_profile_to_v2`).
    #[test]
    fn f12_4_the_export_is_v2_other_apps_read() {
        let set = export_set();
        let one = export_profile_file(&set[..1]).unwrap();
        assert!(one.starts_with("{\n  \""), "pretty");
        let many = export_profile_file(&set).unwrap();
        assert!(many.ends_with('\n'));
        let lines: Vec<&str> = many.lines().collect();
        assert_eq!(lines.len(), set.len());
        for doc in std::iter::once(one.as_str()).chain(lines.iter().copied()) {
            let v: Value = serde_json::from_str(doc).unwrap();
            assert!(v["title"].as_str().is_some_and(|t| !t.is_empty()));
            assert!(v["steps"].as_array().is_some_and(|s| !s.is_empty()));
            for key in ["tank_temperature", "target_volume_count_start"] {
                assert!(!v[key].is_null(), "reaprime requires {key}");
            }
            assert_eq!(v["version"], "2");
            assert_eq!(v["type"], "advanced");
            assert_eq!(v["legacy_profile_type"], "settings_2c");
            assert_eq!(v["lang"], "");
            assert_eq!(v["hidden"], 0);
            // Every step names a pump, sensor and transition other apps know
            // (Decenza `Profile::decaidReadabilityErrors`).
            for s in v["steps"].as_array().unwrap() {
                assert!(["pressure", "flow"].contains(&s["pump"].as_str().unwrap()));
                assert!(["coffee", "water"].contains(&s["sensor"].as_str().unwrap()));
                assert!(["fast", "smooth"].contains(&s["transition"].as_str().unwrap()));
                assert!(s["name"].is_string());
            }
            assert!(import_v2_json(doc).is_ok(), "the core's own v2 reader");
        }
    }

    /// AC-F12.5: export then import gives the same profiles back, apart from
    /// the id and the import's reset — for every built-in, alone and in one
    /// bulk file — and the `crema` extension carries the library metadata.
    #[test]
    fn f12_5_export_then_import_round_trips() {
        let all = builtins();
        let bulk = import_profile_file(&export_profile_file(&all).unwrap());
        assert_eq!(bulk.errors, vec![]);
        assert_eq!(bulk.profiles.len(), all.len());
        for (before, after) in all.iter().zip(bulk.profiles) {
            let single =
                import_profile_file(&export_profile_file(std::slice::from_ref(before)).unwrap());
            assert_eq!(single.errors, vec![], "{}", before.name);
            let expected = comparable(before.clone());
            assert_eq!(
                comparable(single.profiles[0].clone()),
                expected,
                "{} alone",
                before.name
            );
            assert_eq!(comparable(after), expected, "{} in bulk", before.name);
        }
        // The library metadata v2 has no field for.
        let mut p = export_set()[0].clone();
        p.tags = vec!["Mine".into(), "Small".into()];
        p.roast = Some(Roast::Dark);
        p.stop_on_weight = false;
        p.auto_tare = false;
        let back = &import_profile_file(&export_profile_file(std::slice::from_ref(&p)).unwrap())
            .profiles[0];
        assert_eq!(back.tags, p.tags);
        assert_eq!(back.roast, Some(Roast::Dark));
        assert!(!back.stop_on_weight && !back.auto_tare);
    }

    /// The export fixtures are the core's output for [`export_set`]: each
    /// shell's export of the same set must match them byte for byte (AC-F12.6).
    /// Regenerate with `CREMA_REGEN_PROFILE_FIXTURES=1 cargo test -p de1-domain
    /// profile_file`.
    #[test]
    fn the_export_fixtures_are_the_cores_output() {
        let set = export_set();
        let one = export_profile_file(&set[..1]).unwrap();
        let many = export_profile_file(&set).unwrap();
        if std::env::var_os("CREMA_REGEN_PROFILE_FIXTURES").is_some() {
            std::fs::write(format!("{FIXTURES}/profiles/crema-profile.json"), &one).unwrap();
            std::fs::write(format!("{FIXTURES}/profiles/crema-profiles.jsonl"), &many).unwrap();
        }
        assert_eq!(one, fixture("profiles/crema-profile.json"));
        assert_eq!(many, fixture("profiles/crema-profiles.jsonl"));
    }

    /// A pretty-printed profile with a typo is one broken document: one
    /// error with its line and column, not one per line.
    #[test]
    fn a_broken_multi_line_document_is_one_error() {
        let broken = fixture("profiles/web-profile.json").replacen("\"notes\":", "\"notes\"", 1);
        let import = import_profile_file(&broken);
        assert_eq!(import.profiles, vec![]);
        assert_eq!(import.errors.len(), 1, "{:?}", import.errors);
        let message = &import.errors[0].message;
        assert!(message.contains("line 3 column"), "{message}");
        // JSON lines with a bad line still report that line alone.
        let lines = fixture("profiles/web-profiles.jsonl").replacen("\"notes\":", "\"notes\"", 1);
        let import = import_profile_file(&lines);
        assert_eq!((import.profiles.len(), import.errors.len()), (2, 1));
        assert_eq!(import.errors[0].item, Some(1));
    }

    /// At most [`MAX_REPORTED_ERRORS`] errors are listed, then a count.
    #[test]
    fn the_error_list_is_capped() {
        let good = fixture("profiles/web-profiles.jsonl")
            .lines()
            .next()
            .unwrap()
            .to_owned();
        let mut lines = vec![good];
        lines.extend((0..MAX_REPORTED_ERRORS + 10).map(|_| r#"{"name":"x"}"#.to_owned()));
        let import = import_profile_file(&lines.join("\n"));
        assert_eq!(import.profiles.len(), 1);
        assert_eq!(import.errors.len(), MAX_REPORTED_ERRORS + 1);
        assert_eq!(import.errors[MAX_REPORTED_ERRORS - 1].item, Some(21));
        assert_eq!(import.errors[MAX_REPORTED_ERRORS].message, "and 10 more");
    }

    #[test]
    fn a_file_imports_at_most_the_cap() {
        let line = fixture("profiles/web-profiles.jsonl")
            .lines()
            .next()
            .unwrap()
            .to_owned();
        // Distinct titles, so none collapses as a duplicate.
        let text = (0..MAX_PROFILES_PER_FILE + 2)
            .map(|i| line.replacen("\"title\":\"", &format!("\"title\":\"{i} "), 1))
            .collect::<Vec<_>>()
            .join("\n");
        let import = import_profile_file(&text);
        assert_eq!(import.profiles.len(), MAX_PROFILES_PER_FILE);
        assert_eq!(import.errors.len(), 1);
        assert!(import.errors[0].message.starts_with("2 more"));
    }

    #[test]
    fn the_json_faces_round_trip() {
        let set = export_set();
        let text = export_profile_file_json(&serde_json::to_string(&set).unwrap()).unwrap();
        let back: ProfileFileImport =
            serde_json::from_str(&import_profile_file_json(&text, None).unwrap()).unwrap();
        assert_eq!(back.profiles.len(), set.len());
        assert!(export_profile_file_json("{}").is_err());
    }

    /// F12b: a profile already in the library — the same title (trimmed,
    /// case-insensitive) and the same steps — is skipped and counted; a
    /// deliberate copy (another title, or other steps) is imported.
    #[test]
    fn f12b_a_profile_already_in_the_library_is_skipped_and_counted() {
        let library = builtins();
        let blooming = library
            .iter()
            .find(|p| p.name == "Blooming Allongé")
            .unwrap()
            .clone();
        // A re-import of a built-in's export.
        let import = import_profile_file_into(
            &export_profile_file(std::slice::from_ref(&blooming)).unwrap(),
            &library,
        );
        assert_eq!((import.profiles.len(), import.skipped_duplicates), (0, 1));
        // The title is compared trimmed and case-insensitively.
        let mut renamed = blooming.clone();
        renamed.name = " blooming allongé  ".to_owned();
        let import = import_profile_file_into(
            &export_profile_file(std::slice::from_ref(&renamed)).unwrap(),
            &library,
        );
        assert_eq!(import.skipped_duplicates, 1);
        // Another title, or other steps: imported.
        let mut copy = blooming.clone();
        copy.name = "My Blooming".to_owned();
        let mut edited = blooming.clone();
        edited.segments[0].temp += 1.0;
        let import =
            import_profile_file_into(&export_profile_file(&[copy, edited]).unwrap(), &library);
        assert_eq!((import.profiles.len(), import.skipped_duplicates), (2, 0));
        // Duplicates inside one file collapse to the first.
        let mut a = blooming.clone();
        a.name = "Twice".to_owned();
        let import = import_profile_file_into(&export_profile_file(&[a.clone(), a]).unwrap(), &[]);
        assert_eq!((import.profiles.len(), import.skipped_duplicates), (1, 1));
        // The JSON face takes the library.
        let text = export_profile_file(std::slice::from_ref(&blooming)).unwrap();
        let back: ProfileFileImport = serde_json::from_str(
            &import_profile_file_json(&text, Some(&serde_json::to_string(&library).unwrap()))
                .unwrap(),
        )
        .unwrap();
        assert_eq!(back.skipped_duplicates, 1);
        // A library element that isn't a profile is left out, not fatal.
        let odd = format!(
            "[{{\"name\":\"x\"}},{}]",
            serde_json::to_string(&blooming).unwrap()
        );
        let back: ProfileFileImport =
            serde_json::from_str(&import_profile_file_json(&text, Some(&odd)).unwrap()).unwrap();
        assert_eq!(back.skipped_duplicates, 1);
        assert!(import_profile_file_json(&text, Some("{}")).is_err());
    }
}

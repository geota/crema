//! Restoring a backup: read it strictly, then plan the whole restore before
//! anything is written (restore-safety design, sections 1 and 3).
//!
//! **The core takes bytes.** A backup is split on `0x0A`, and each line is
//! strict UTF-8 and strict JSON, so one bad byte costs one line and the rule
//! exists once. [`scan_backup_bytes`] reads ids and the few facts a plan needs
//! into a [`BackupManifest`]; [`import_backup_bytes`] reads the records under
//! the same line rules. [`plan_restore`] compares the manifest with what the
//! device holds ([`LocalState`]) and returns one [`RestorePlan`] of ids and
//! decisions, no row data, which the shells apply and decide nothing about.
//!
//! **Merge only adds what's missing.** It never changes or removes what's
//! here, whatever the timestamps say. The one exception is a curve: a row here
//! with none takes the backup's ([`ShotPlan::fill`]). Replace makes the device
//! match the backup for every store the backup carries, and leaves alone the
//! stores it doesn't.
//!
//! **A store the backup doesn't carry is kept.** For a row store that's a
//! count missing from the header (`Some(0)` is carried and empty); for a
//! settings-like line it's no line.

use std::collections::{BTreeMap, HashMap, HashSet};

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::{
    Bean, BrewRecipe, CustomBrewMethod, Roaster, STORED_SHOT_FORMAT_VERSION, StoredShot,
    app_settings::CommonSettings, crema_jsonl::BackupImportPlan, is_builtin_recipe,
    is_custom_method_id, visualizer_sync::VisualizerSyncPrefs,
};

/// The header kind this build writes and reads.
const BACKUP_KIND: &str = "crema-backup/v1";

/// The header of a History "Export shots" file, which Import reads.
const SHOTS_EXPORT_KIND: &str = "crema/v1";

/// The start of every backup header kind; any other version is newer.
const BACKUP_KIND_PREFIX: &str = "crema-backup/";

/// How a built-in profile's id starts: never restored from a backup.
const BUILTIN_PROFILE_PREFIX: &str = "builtin:";

/// Problems a manifest lists by line; the totals count them all.
const PROBLEMS_LISTED: usize = 20;

// ── Lines ──────────────────────────────────────────────────────────────────

/// What's wrong with one line.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum LineError {
    /// Not UTF-8.
    Utf8,
    /// Not JSON.
    Json,
    /// JSON, but not an object.
    NotObject,
    /// An object with no `kind` string.
    NoKind,
    /// A row whose kind is known, but that doesn't read as one.
    BadRow,
    /// A header or shot from a newer build than this one.
    NewerFormat,
}

/// The settings-like lines: one of each, applied whole or not at all.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Singleton {
    Settings,
    ProfileMeta,
    RecipeMeta,
    Maintenance,
    VisualizerPrefs,
}

/// One line of a backup, read.
#[derive(Debug, Clone, PartialEq)]
pub enum BackupLine {
    Header(ManifestHeader),
    /// A shot, and whether its raw line said it was pinned.
    Shot(Box<StoredShot>, bool),
    Bean(Box<Bean>),
    Roaster(Box<Roaster>),
    Recipe(Box<BrewRecipe>),
    Method(Box<CustomBrewMethod>),
    /// A custom profile, verbatim with `kind` removed, and its id.
    Profile(serde_json::Value, String),
    /// A settings-like line, verbatim with `kind` removed.
    Singleton(Singleton, serde_json::Value),
    /// A `kind` this build doesn't know: skipped, and noted.
    Unknown(String),
    /// The header of a shots export (`crema/v1`): not a backup.
    ShotsExport,
}

/// A backup's header, with each store's count `None` when the header doesn't
/// carry it: that store isn't in the backup, which is not the same as empty.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ManifestHeader {
    pub created_at: i64,
    pub app_version: String,
    pub device_label: String,
    pub profile_count: Option<u32>,
    pub bean_count: Option<u32>,
    pub roaster_count: Option<u32>,
    pub shot_count: Option<u32>,
    pub recipe_count: Option<u32>,
    pub custom_method_count: Option<u32>,
    pub omitted_count: u32,
}

/// One backup line, read strictly by [`parse_backup_line`], in the shape the
/// shells read line by line (#310 §7): tagged by `line`.
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "line", rename_all = "camelCase")]
pub enum ParsedBackupLine {
    Header {
        header: ManifestHeader,
    },
    /// A shot, and whether its raw line said it was pinned.
    Shot {
        shot: Box<StoredShot>,
        pinned: bool,
    },
    Bean {
        bean: Box<Bean>,
    },
    Roaster {
        roaster: Box<Roaster>,
    },
    Recipe {
        recipe: Box<BrewRecipe>,
    },
    BrewMethod {
        method: Box<CustomBrewMethod>,
    },
    /// A custom profile, verbatim with `kind` removed.
    Profile {
        id: String,
        profile: serde_json::Value,
    },
    /// A settings-like line by its kind (`settings`, `profileMeta`,
    /// `recipeMeta`, `maintenance`, `visualizerPrefs`), verbatim.
    Singleton {
        kind: &'static str,
        value: serde_json::Value,
    },
    /// A `kind` this build doesn't know.
    Unknown {
        kind: String,
    },
    /// A shots export's header: not a backup.
    ShotsExport,
    /// What's wrong with the line.
    Error {
        error: LineError,
    },
}

impl Singleton {
    /// The line's `kind`.
    #[must_use]
    pub fn kind(self) -> &'static str {
        match self {
            Self::Settings => "settings",
            Self::ProfileMeta => "profileMeta",
            Self::RecipeMeta => "recipeMeta",
            Self::Maintenance => "maintenance",
            Self::VisualizerPrefs => "visualizerPrefs",
        }
    }
}

/// [`parse_backup_line`] in the shells' shape.
#[must_use]
pub fn parse_backup_line_parsed(line: &[u8]) -> ParsedBackupLine {
    match parse_backup_line(line) {
        Err(error) => ParsedBackupLine::Error { error },
        Ok(BackupLine::Header(header)) => ParsedBackupLine::Header { header },
        Ok(BackupLine::Shot(shot, pinned)) => ParsedBackupLine::Shot { shot, pinned },
        Ok(BackupLine::Bean(bean)) => ParsedBackupLine::Bean { bean },
        Ok(BackupLine::Roaster(roaster)) => ParsedBackupLine::Roaster { roaster },
        Ok(BackupLine::Recipe(recipe)) => ParsedBackupLine::Recipe { recipe },
        Ok(BackupLine::Method(method)) => ParsedBackupLine::BrewMethod { method },
        Ok(BackupLine::Profile(profile, id)) => ParsedBackupLine::Profile { id, profile },
        Ok(BackupLine::Singleton(which, value)) => ParsedBackupLine::Singleton {
            kind: which.kind(),
            value,
        },
        Ok(BackupLine::Unknown(kind)) => ParsedBackupLine::Unknown { kind },
        Ok(BackupLine::ShotsExport) => ParsedBackupLine::ShotsExport,
    }
}

/// [`parse_backup_line`] over JSON, for the bridges: one line in, without its
/// newline, a [`ParsedBackupLine`] out.
#[must_use]
pub fn parse_backup_line_json(line: &[u8]) -> String {
    serde_json::to_string(&parse_backup_line_parsed(line)).unwrap_or_default()
}

/// Read one line, strictly: UTF-8, then a JSON object with a `kind`, then a
/// row that reads as its kind. Blank lines are the caller's to skip.
///
/// # Errors
///
/// What's wrong with the line.
pub fn parse_backup_line(line: &[u8]) -> Result<BackupLine, LineError> {
    let text = std::str::from_utf8(line).map_err(|_| LineError::Utf8)?;
    let value: serde_json::Value = serde_json::from_str(text).map_err(|_| LineError::Json)?;
    let serde_json::Value::Object(mut map) = value else {
        return Err(LineError::NotObject);
    };
    let kind = match map.get("kind") {
        Some(serde_json::Value::String(k)) => k.clone(),
        _ => return Err(LineError::NoKind),
    };
    if kind == SHOTS_EXPORT_KIND {
        return Ok(BackupLine::ShotsExport);
    }
    if kind.starts_with(BACKUP_KIND_PREFIX) {
        if kind != BACKUP_KIND {
            return Err(LineError::NewerFormat);
        }
        return read_header(&map).map(BackupLine::Header);
    }
    let singleton = match kind.as_str() {
        "settings" => Some(Singleton::Settings),
        "profileMeta" => Some(Singleton::ProfileMeta),
        "recipeMeta" => Some(Singleton::RecipeMeta),
        "maintenance" => Some(Singleton::Maintenance),
        "visualizerPrefs" => Some(Singleton::VisualizerPrefs),
        _ => None,
    };
    if let Some(singleton) = singleton {
        map.remove("kind");
        return Ok(BackupLine::Singleton(
            singleton,
            serde_json::Value::Object(map),
        ));
    }
    let row = serde_json::Value::Object(map);
    match kind.as_str() {
        "shot" => {
            let newer = row
                .get("formatVersion")
                .and_then(serde_json::Value::as_u64)
                .is_some_and(|v| v > u64::from(STORED_SHOT_FORMAT_VERSION));
            if newer {
                return Err(LineError::NewerFormat);
            }
            let pinned = row
                .get("pinned")
                .and_then(serde_json::Value::as_bool)
                .unwrap_or(false);
            if row.get("id").and_then(serde_json::Value::as_str).is_none() {
                return Err(LineError::BadRow);
            }
            let shot: StoredShot = typed(row)?;
            Ok(BackupLine::Shot(Box::new(shot), pinned))
        }
        "bean" => Ok(BackupLine::Bean(Box::new(typed(row)?))),
        "roaster" => Ok(BackupLine::Roaster(Box::new(typed(row)?))),
        "recipe" => Ok(BackupLine::Recipe(Box::new(typed(row)?))),
        "brewMethod" => Ok(BackupLine::Method(Box::new(typed(row)?))),
        "profile" => {
            let mut row = row;
            let id = row
                .get("id")
                .and_then(serde_json::Value::as_str)
                .filter(|id| !id.is_empty())
                .ok_or(LineError::BadRow)?
                .to_owned();
            if let Some(map) = row.as_object_mut() {
                map.remove("kind");
            }
            Ok(BackupLine::Profile(row, id))
        }
        _ => Ok(BackupLine::Unknown(kind)),
    }
}

fn typed<T: serde::de::DeserializeOwned>(row: serde_json::Value) -> Result<T, LineError> {
    serde_json::from_value(row).map_err(|_| LineError::BadRow)
}

fn read_header(
    map: &serde_json::Map<String, serde_json::Value>,
) -> Result<ManifestHeader, LineError> {
    let count = |key: &str| -> Result<Option<u32>, LineError> {
        match map.get(key) {
            None => Ok(None),
            Some(v) => v
                .as_u64()
                .and_then(|n| u32::try_from(n).ok())
                .map(Some)
                .ok_or(LineError::BadRow),
        }
    };
    let text = |key: &str| {
        map.get(key)
            .and_then(serde_json::Value::as_str)
            .unwrap_or("")
            .to_owned()
    };
    Ok(ManifestHeader {
        created_at: map
            .get("createdAt")
            .and_then(serde_json::Value::as_i64)
            .ok_or(LineError::BadRow)?,
        app_version: text("appVersion"),
        device_label: text("deviceLabel"),
        profile_count: count("profileCount")?,
        bean_count: count("beanCount")?,
        roaster_count: count("roasterCount")?,
        shot_count: count("shotCount")?,
        recipe_count: count("recipeCount")?,
        custom_method_count: count("customMethodCount")?,
        omitted_count: count("omittedCount")?.unwrap_or(0),
    })
}

/// `bytes` split on `0x0A`, numbered from 1, blank lines left out.
fn lines(bytes: &[u8]) -> impl Iterator<Item = (u32, &[u8])> {
    bytes
        .split(|&b| b == b'\n')
        .zip(1_u32..)
        .filter(|(line, _)| !line.iter().all(u8::is_ascii_whitespace))
        .map(|(line, n)| (n, line))
}

// ── The manifest ───────────────────────────────────────────────────────────

/// A store that holds rows.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum RowStore {
    Beans,
    Roasters,
    Shots,
    Profiles,
    Recipes,
    CustomMethods,
}

impl RowStore {
    const ALL: [RowStore; 6] = [
        RowStore::Beans,
        RowStore::Roasters,
        RowStore::Shots,
        RowStore::Profiles,
        RowStore::Recipes,
        RowStore::CustomMethods,
    ];

    /// The store's name in a plan note.
    #[must_use]
    pub fn name(self) -> &'static str {
        match self {
            RowStore::Beans => "beans",
            RowStore::Roasters => "roasters",
            RowStore::Shots => "shots",
            RowStore::Profiles => "profiles",
            RowStore::Recipes => "recipes",
            RowStore::CustomMethods => "customMethods",
        }
    }

    fn header_count(self, h: &ManifestHeader) -> Option<u32> {
        match self {
            RowStore::Beans => h.bean_count,
            RowStore::Roasters => h.roaster_count,
            RowStore::Shots => h.shot_count,
            RowStore::Profiles => h.profile_count,
            RowStore::Recipes => h.recipe_count,
            RowStore::CustomMethods => h.custom_method_count,
        }
    }
}

/// One problem, at a line.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LineProblem {
    pub line: u32,
    pub issue: LineIssue,
    /// The store, for a duplicate or a count mismatch.
    pub store: Option<RowStore>,
}

/// What a line's problem is.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum LineIssue {
    Utf8,
    Json,
    NotObject,
    NoKind,
    BadRow,
    NewerFormat,
    /// The first line isn't a header that reads.
    HeaderDamaged,
    /// A header anywhere but the first line.
    HeaderNotFirst,
    /// A second row with an id already seen: the first one wins.
    Duplicate,
    /// A kind this build doesn't know.
    UnknownKind,
    /// A second settings-like line of one kind: the first one wins.
    DuplicateLine,
}

impl From<LineError> for LineIssue {
    fn from(e: LineError) -> Self {
        match e {
            LineError::Utf8 => LineIssue::Utf8,
            LineError::Json => LineIssue::Json,
            LineError::NotObject => LineIssue::NotObject,
            LineError::NoKind => LineIssue::NoKind,
            LineError::BadRow => LineIssue::BadRow,
            LineError::NewerFormat => LineIssue::NewerFormat,
        }
    }
}

/// A shot in a backup, as far as a plan needs it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ManifestShot {
    pub id: String,
    pub bean_id: Option<String>,
    pub has_samples: bool,
    pub completed_at: u64,
    pub pinned: bool,
}

/// A store's count in the header against the rows its lines held.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CountMismatch {
    pub store: RowStore,
    pub header: u32,
    pub lines: u32,
}

/// Everything a plan needs from a backup: ids, not rows. About 2.5 MB at
/// 20,000 shots.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BackupManifest {
    /// The header, when the first line is one that reads.
    pub header: Option<ManifestHeader>,
    /// The first line was meant to be a header and isn't one that reads, while
    /// other lines are a backup's. Damaged, not "not a backup".
    pub header_damaged: bool,
    /// A header or a shot from a newer build.
    pub newer_format: bool,
    /// A History "Export shots" file: Import reads it, not restore.
    pub shots_export: bool,
    /// Ids per store, in file order, first of each id only.
    pub beans: Vec<String>,
    pub roasters: Vec<String>,
    pub profiles: Vec<String>,
    pub recipes: Vec<String>,
    pub custom_methods: Vec<String>,
    pub shots: Vec<ManifestShot>,
    /// Bean ids with a photo in the backup. The shell's, from the zip.
    pub photos: Vec<String>,
    /// The settings line's `_shell`, if there's a settings line.
    pub settings: Option<SettingsLine>,
    pub profile_meta: Option<serde_json::Value>,
    pub recipe_meta: Option<serde_json::Value>,
    pub has_maintenance: bool,
    pub has_visualizer_prefs: bool,
    /// The first [`PROBLEMS_LISTED`] problems.
    pub problems: Vec<LineProblem>,
    pub bad_lines: u32,
    pub unknown_kinds: u32,
    pub duplicates: u32,
    pub count_mismatches: Vec<CountMismatch>,
    /// Lines that read as any part of a backup.
    pub good_lines: u32,
}

/// What a backup's settings line says about itself.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SettingsLine {
    /// The shell that wrote it (`web` or `android`), when it says.
    pub shell: Option<String>,
}

impl BackupManifest {
    fn ids(&self, store: RowStore) -> Vec<&str> {
        let list = match store {
            RowStore::Beans => &self.beans,
            RowStore::Roasters => &self.roasters,
            RowStore::Profiles => &self.profiles,
            RowStore::Recipes => &self.recipes,
            RowStore::CustomMethods => &self.custom_methods,
            RowStore::Shots => return self.shots.iter().map(|s| s.id.as_str()).collect(),
        };
        list.iter().map(String::as_str).collect()
    }

    /// Whether the backup carries `store`: its header has the count, or, with
    /// no header to say, it has a line of it.
    #[must_use]
    pub fn carries(&self, store: RowStore) -> bool {
        match &self.header {
            Some(h) => store.header_count(h).is_some(),
            None => !self.ids(store).is_empty(),
        }
    }

    /// Rows in the stores this backup carries.
    fn counted_rows(&self) -> usize {
        RowStore::ALL
            .iter()
            .filter(|s| self.carries(**s))
            .map(|s| self.ids(*s).len())
            .sum()
    }

    /// Whether any line read as part of a backup.
    fn is_backup(&self) -> bool {
        self.header.is_some() || self.header_damaged || self.good_lines > 0
    }
}

/// [`scan_backup_bytes`], a line at a time, for a backup read in pieces.
#[derive(Debug, Default)]
pub struct BackupScan {
    manifest: BackupManifest,
    line: u32,
    seen_content: bool,
    first_line_failed: Option<u32>,
    lines_per_store: HashMap<RowStore, u32>,
    ids: HashMap<RowStore, HashSet<String>>,
    singletons_seen: Vec<SingletonKey>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum SingletonKey {
    Settings,
    ProfileMeta,
    RecipeMeta,
    Maintenance,
    VisualizerPrefs,
}

impl BackupScan {
    /// A new scan.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    fn problem(&mut self, issue: LineIssue, store: Option<RowStore>) {
        if self.manifest.problems.len() < PROBLEMS_LISTED {
            self.manifest.problems.push(LineProblem {
                line: self.line,
                issue,
                store,
            });
        }
    }

    fn row(&mut self, store: RowStore, id: &str) -> bool {
        *self.lines_per_store.entry(store).or_default() += 1;
        let fresh = self.ids.entry(store).or_default().insert(id.to_owned());
        if !fresh {
            self.manifest.duplicates += 1;
            self.problem(LineIssue::Duplicate, Some(store));
        }
        fresh
    }

    /// Read the next line, `0x0A` not included.
    pub fn push_line(&mut self, line: &[u8]) {
        self.line += 1;
        if line.iter().all(u8::is_ascii_whitespace) {
            return;
        }
        let first = !self.seen_content;
        self.seen_content = true;
        let parsed = match parse_backup_line(line) {
            Ok(parsed) => parsed,
            Err(e) => {
                if e == LineError::NewerFormat {
                    self.manifest.newer_format = true;
                }
                if first {
                    self.first_line_failed = Some(self.line);
                } else {
                    self.manifest.bad_lines += 1;
                    self.problem(e.into(), None);
                }
                return;
            }
        };
        if first && !matches!(parsed, BackupLine::Header(_)) {
            // A file whose first line isn't a header: the header is missing.
            self.first_line_failed = Some(self.line);
        }
        match parsed {
            BackupLine::Header(h) => {
                if first {
                    self.manifest.header = Some(h);
                } else {
                    self.manifest.bad_lines += 1;
                    self.problem(LineIssue::HeaderNotFirst, None);
                }
                return;
            }
            BackupLine::ShotsExport => {
                if first {
                    self.manifest.shots_export = true;
                } else {
                    self.manifest.unknown_kinds += 1;
                    self.problem(LineIssue::UnknownKind, None);
                }
                return;
            }
            BackupLine::Unknown(_) => {
                self.manifest.unknown_kinds += 1;
                self.problem(LineIssue::UnknownKind, None);
                return;
            }
            BackupLine::Shot(shot, pinned) => {
                if self.row(RowStore::Shots, &shot.id) {
                    self.manifest.shots.push(ManifestShot {
                        bean_id: shot.bean.as_ref().and_then(|b| b.bean_id.clone()),
                        has_samples: !shot.record.samples.is_empty(),
                        completed_at: shot.completed_at,
                        pinned,
                        id: shot.id,
                    });
                }
            }
            BackupLine::Bean(b) => {
                if self.row(RowStore::Beans, &b.id) {
                    self.manifest.beans.push(b.id);
                }
            }
            BackupLine::Roaster(r) => {
                if self.row(RowStore::Roasters, &r.id) {
                    self.manifest.roasters.push(r.id);
                }
            }
            BackupLine::Recipe(r) => {
                if self.row(RowStore::Recipes, &r.id) {
                    self.manifest.recipes.push(r.id);
                }
            }
            BackupLine::Method(m) => {
                if self.row(RowStore::CustomMethods, &m.id) {
                    self.manifest.custom_methods.push(m.id);
                }
            }
            BackupLine::Profile(_, id) => {
                if self.row(RowStore::Profiles, &id) {
                    self.manifest.profiles.push(id);
                }
            }
            BackupLine::Singleton(kind, value) => self.singleton(kind, value),
        }
        self.manifest.good_lines += 1;
    }

    fn singleton(&mut self, kind: Singleton, value: serde_json::Value) {
        let key = match kind {
            Singleton::Settings => SingletonKey::Settings,
            Singleton::ProfileMeta => SingletonKey::ProfileMeta,
            Singleton::RecipeMeta => SingletonKey::RecipeMeta,
            Singleton::Maintenance => SingletonKey::Maintenance,
            Singleton::VisualizerPrefs => SingletonKey::VisualizerPrefs,
        };
        if self.singletons_seen.contains(&key) {
            self.manifest.duplicates += 1;
            self.problem(LineIssue::DuplicateLine, None);
            return;
        }
        self.singletons_seen.push(key);
        let m = &mut self.manifest;
        match kind {
            Singleton::Settings => {
                m.settings = Some(SettingsLine {
                    shell: value
                        .get("_shell")
                        .and_then(serde_json::Value::as_str)
                        .map(str::to_owned),
                });
            }
            Singleton::ProfileMeta => m.profile_meta = Some(value),
            Singleton::RecipeMeta => m.recipe_meta = Some(value),
            Singleton::Maintenance => m.has_maintenance = true,
            Singleton::VisualizerPrefs => m.has_visualizer_prefs = true,
        }
    }

    /// The manifest, with its count checks.
    #[must_use]
    pub fn finish(mut self) -> BackupManifest {
        if let Some(line) = self.first_line_failed
            && self.manifest.good_lines > 0
        {
            // Other lines read: a backup whose header is damaged or gone.
            self.manifest.header_damaged = true;
            self.manifest.bad_lines += 1;
            if self.manifest.problems.len() < PROBLEMS_LISTED {
                self.manifest.problems.insert(
                    0,
                    LineProblem {
                        line,
                        issue: LineIssue::HeaderDamaged,
                        store: None,
                    },
                );
            }
        }
        if let Some(header) = &self.manifest.header {
            for store in RowStore::ALL {
                if let Some(expected) = store.header_count(header) {
                    let lines = self.lines_per_store.get(&store).copied().unwrap_or(0);
                    if lines != expected {
                        self.manifest.count_mismatches.push(CountMismatch {
                            store,
                            header: expected,
                            lines,
                        });
                    }
                }
            }
        }
        self.manifest
    }
}

/// Scan a whole backup into its [`BackupManifest`]. See [`BackupScan`].
#[must_use]
pub fn scan_backup_bytes(bytes: &[u8]) -> BackupManifest {
    let mut scan = BackupScan::new();
    for line in bytes.split(|&b| b == b'\n') {
        scan.push_line(line);
    }
    scan.finish()
}

/// A backup's records, under the same line rules as [`scan_backup_bytes`]: a
/// line that doesn't read is left out, and the first row of an id wins. The
/// shell applies a plan's ids from these.
#[must_use]
pub fn import_backup_bytes(bytes: &[u8]) -> BackupImportPlan {
    let mut plan = BackupImportPlan::default();
    let mut seen: HashMap<RowStore, HashSet<String>> = HashMap::new();
    let mut fresh =
        |store: RowStore, id: &str| seen.entry(store).or_default().insert(id.to_owned());
    let mut first = true;
    for (_, line) in lines(bytes) {
        let parsed = parse_backup_line(line);
        let was_first = std::mem::replace(&mut first, false);
        let Ok(parsed) = parsed else { continue };
        match parsed {
            BackupLine::Header(_) => plan.has_header |= was_first,
            BackupLine::Shot(s, _) if fresh(RowStore::Shots, &s.id) => plan.shots.push(*s),
            BackupLine::Bean(b) if fresh(RowStore::Beans, &b.id) => plan.beans.push(*b),
            BackupLine::Roaster(r) if fresh(RowStore::Roasters, &r.id) => plan.roasters.push(*r),
            BackupLine::Recipe(r)
                if !is_builtin_recipe(&r.id) && fresh(RowStore::Recipes, &r.id) =>
            {
                plan.recipes.push(*r);
            }
            BackupLine::Method(m)
                if is_custom_method_id(&m.id) && fresh(RowStore::CustomMethods, &m.id) =>
            {
                plan.custom_methods.push(*m);
            }
            BackupLine::Profile(p, id) if fresh(RowStore::Profiles, &id) => plan.profiles.push(p),
            BackupLine::Singleton(kind, value) => {
                let slot = match kind {
                    Singleton::Settings => &mut plan.settings,
                    Singleton::ProfileMeta => &mut plan.profile_meta,
                    Singleton::RecipeMeta => &mut plan.recipe_meta,
                    Singleton::Maintenance => &mut plan.maintenance,
                    Singleton::VisualizerPrefs => &mut plan.visualizer_prefs,
                };
                if slot.is_none() {
                    *slot = Some(value);
                }
            }
            _ => {}
        }
    }
    plan
}

// ── What's here ────────────────────────────────────────────────────────────

/// How a store on the device loaded.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum LocalHealth {
    Loading,
    Ok,
    Absent,
    Damaged,
    Unavailable,
}

/// One store's health, named as the shell names it.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalStoreHealth {
    pub store: String,
    pub health: LocalHealth,
}

/// A shot here, as far as a plan needs it. Include tombstoned rows.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalShot {
    pub id: String,
    pub has_samples: bool,
    #[serde(default)]
    pub pinned: bool,
    /// Deleted here, with its remote delete still owed.
    #[serde(default)]
    pub deleted: bool,
}

/// Profile organisation here.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct ProfileMeta {
    pub pinned: Vec<String>,
    pub hidden_builtins: Vec<String>,
}

/// Recipe organisation here.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct RecipeMeta {
    /// Method id → recipe id. Written in order, so a plan serialises the same
    /// way every time: the shell compares a confirmed plan with a fresh one by
    /// its JSON.
    #[serde(serialize_with = "ordered")]
    pub defaults: HashMap<String, String>,
    pub hidden_builtins: Vec<String>,
}

/// What the device holds, from the shell. Built once telemetry has loaded:
/// before that every row looks curveless.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct LocalState {
    /// `web` or `android`.
    pub shell: String,
    /// Rows history keeps, or none when it has no cap.
    pub history_capacity: Option<u32>,
    /// A shot or a firmware flash is running, in any tab.
    pub machine_busy: bool,
    pub health: Vec<LocalStoreHealth>,
    pub beans: Vec<String>,
    pub roasters: Vec<String>,
    /// Custom profiles only: built-ins aren't the backup's to change.
    pub profiles: Vec<String>,
    /// The user's own recipes only.
    pub recipes: Vec<String>,
    /// Tombstones included.
    pub custom_methods: Vec<String>,
    pub shots: Vec<LocalShot>,
    /// Bean ids with a stored photo.
    pub photos: Vec<String>,
    /// Bean ids whose row names a photo that isn't there.
    pub beans_missing_photo: Vec<String>,
    pub profile_meta: ProfileMeta,
    pub recipe_meta: RecipeMeta,
    pub common: CommonSettings,
    pub visualizer_prefs: VisualizerSyncPrefs,
    pub total_litres: f64,
    pub active_bean_id: Option<String>,
    /// Beans an earlier, interrupted run of this restore added, from its
    /// marker. They don't count as here for `zero_debit`: a re-run after any
    /// prefix of the steps must end where a clean run does.
    pub added_beans: Vec<String>,
}

impl LocalState {
    fn ids(&self, store: RowStore) -> Vec<&str> {
        let list = match store {
            RowStore::Beans => &self.beans,
            RowStore::Roasters => &self.roasters,
            RowStore::Profiles => &self.profiles,
            RowStore::Recipes => &self.recipes,
            RowStore::CustomMethods => &self.custom_methods,
            RowStore::Shots => {
                return self
                    .shots
                    .iter()
                    .filter(|s| !s.deleted)
                    .map(|s| s.id.as_str())
                    .collect();
            }
        };
        list.iter().map(String::as_str).collect()
    }
}

// ── The plan ───────────────────────────────────────────────────────────────

/// Merge adds what's missing; Replace makes the device match the backup.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum RestoreMode {
    Merge,
    Replace,
}

/// What the user chose in the report.
#[typeshare]
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct RestoreOpts {
    /// Bring back shots deleted here that the backup has.
    pub revive_deleted: bool,
}

/// Whether the shell may apply the plan at once.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Verdict {
    /// Apply, then report.
    Ok,
    /// Show the report first.
    Confirm,
    /// Change nothing.
    Refused,
}

/// Why a plan won't run, or what its report must say.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum PlanCode {
    NotABackup,
    /// A History "Export shots" file: use Import.
    ShotsExport,
    NewerFormat,
    StoreUnavailable,
    StoreLoading,
    MachineBusy,
    BadLines,
    HeaderDamaged,
    CountMismatch,
    OverCapacity,
    EmptyBackup,
    NotAdded,
    Omitted,
    PinnedRemoved,
    UnknownKinds,
    Duplicates,
    DeletedHere,
}

/// One refusal or warning.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlanNote {
    pub code: PlanCode,
    /// A warning the user has to see before it runs, not one only noted.
    pub confirm: bool,
    /// How many, where that's the point.
    pub count: Option<u32>,
    /// The store, where there's one.
    pub store: Option<String>,
}

impl PlanNote {
    fn new(code: PlanCode) -> Self {
        Self {
            code,
            confirm: true,
            count: None,
            store: None,
        }
    }

    fn count(mut self, n: usize) -> Self {
        self.count = Some(u32::try_from(n).unwrap_or(u32::MAX));
        self
    }

    fn count32(mut self, n: u32) -> Self {
        self.count = Some(n);
        self
    }

    fn store(mut self, store: impl Into<String>) -> Self {
        self.store = Some(store.into());
        self
    }

    fn noted(mut self) -> Self {
        self.confirm = false;
        self
    }
}

/// Where the backup came from.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlanSource {
    #[typeshare(serialized_as = "I64")]
    pub created_at: i64,
    pub app_version: String,
    pub device_label: String,
    pub omitted_count: u32,
}

/// One row store's part of a plan.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StorePlan {
    /// Ids in the backup and not here.
    pub add: Vec<String>,
    /// Ids in both, the backup's row to win. Replace only.
    pub overwrite: Vec<String>,
    /// Rows in both that stay as they are.
    pub keep: u32,
    /// Ids here to delete, by name. Replace only.
    pub remove: Vec<String>,
}

/// The shots' part of a plan.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ShotPlan {
    pub add: Vec<String>,
    pub overwrite: Vec<String>,
    pub keep: u32,
    pub remove: Vec<String>,
    /// Rows here with no curve, which take the backup's curve and nothing else.
    pub fill: Vec<String>,
    /// Added rows on a bag that's already here: that bag never paid for them,
    /// so their `bagDebit` becomes 0.
    pub zero_debit: Vec<String>,
    /// Rows the backup has that were deleted here: skipped, and offered back.
    pub deleted_here: Vec<String>,
    /// Rows here as tombstones that come back as the backup's row: on Replace,
    /// and on Merge with "bring back". Their tombstones are cancelled.
    pub revive: Vec<String>,
    /// Rows Merge couldn't add, as history is full, revived ones included.
    pub not_added: u32,
    /// Shots asked back that history had no room for: they stay deleted.
    pub not_revived: Vec<String>,
    /// Pinned rows Replace removes.
    pub pinned_removed: u32,
}

/// Tombstones to drop, with their queued remote deletes.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TombstonePlan {
    pub cancel: Vec<String>,
}

/// Bag photos, by bean id.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PhotoPlan {
    pub put: Vec<String>,
    pub remove: Vec<String>,
}

/// Apply the backup's, or keep what's here.
#[typeshare]
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum PlanAction {
    #[default]
    Keep,
    Apply,
}

/// The settings line's part of a plan.
#[typeshare]
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SettingsPlan {
    /// The shared settings. Replace resets, then applies.
    pub common: PlanAction,
    /// The shell's own: only from a backup the same shell wrote.
    pub extras: PlanAction,
}

/// Profile organisation after the restore, when it changes.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileMetaPlan {
    pub action: PlanAction,
    pub pinned: Vec<String>,
    pub hidden_builtins: Vec<String>,
}

/// Recipe organisation after the restore, when it changes.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RecipeMetaPlan {
    pub action: PlanAction,
    /// Written in order, as [`RecipeMeta::defaults`].
    #[serde(serialize_with = "ordered")]
    pub defaults: HashMap<String, String>,
    pub hidden_builtins: Vec<String>,
}

/// One restore, decided: ids and actions, no row data.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RestorePlan {
    pub mode: RestoreMode,
    pub verdict: Verdict,
    pub refusals: Vec<PlanNote>,
    pub warnings: Vec<PlanNote>,
    pub source: PlanSource,
    pub beans: StorePlan,
    pub roasters: StorePlan,
    pub profiles: StorePlan,
    pub recipes: StorePlan,
    pub custom_methods: StorePlan,
    pub shots: ShotPlan,
    pub tombstones: TombstonePlan,
    pub photos: PhotoPlan,
    pub settings: SettingsPlan,
    pub maintenance: PlanAction,
    pub visualizer_prefs: PlanAction,
    pub profile_meta: ProfileMetaPlan,
    pub recipe_meta: RecipeMetaPlan,
    /// Replace clears an active bean the backup doesn't have.
    pub clear_active_bean: bool,
}

/// Whether `common` is still as a fresh install leaves it. Not "equals the
/// defaults": a fresh install's first load fills the hot-water dose and the
/// idle-sleep timer, so both sides get those filled by the same rules first.
#[must_use]
pub fn common_is_pristine(common: &CommonSettings) -> bool {
    first_run(common.clone()) == first_run(CommonSettings::default())
}

/// What a first load does to `c`: the settings sanitize, the idle-sleep
/// default and the hot-water never-changed rule.
fn first_run(c: CommonSettings) -> CommonSettings {
    let mut c = c.sanitized();
    if c.idle_sleep_min.is_none() {
        c.idle_sleep_min = Some(crate::idle_sleep::DEFAULT_IDLE_SLEEP_MIN);
    }
    if c.qc_hot_water_mode.is_none() {
        let dose = crate::hot_water::initial_hot_water_dose(
            &c.stored_hot_water(),
            None,
            &crate::hot_water::HOT_WATER_FIRMWARE,
        );
        c.qc_hot_water_mode = Some(dose.mode);
        c.qc_hot_water_time_s = Some(dose.time_s);
        c.qc_hot_water_time_no_limit = Some(dose.time_no_limit);
        c.qc_hot_water_volume_ml = dose.volume_ml;
        c.qc_hot_water_ratio = Some(dose.ratio);
    }
    c
}

/// Whether `id` names a built-in profile: one of the corpus's ids, or the
/// `builtin:` form older builds wrote.
fn is_builtin_profile(id: &str) -> bool {
    static IDS: std::sync::OnceLock<HashSet<&'static str>> = std::sync::OnceLock::new();
    id.starts_with(BUILTIN_PROFILE_PREFIX)
        || IDS
            .get_or_init(|| {
                crate::builtin_profiles()
                    .iter()
                    .map(|p| p.id.as_str())
                    .collect()
            })
            .contains(id)
}

/// Whether `id` is a row a backup can't put here: a built-in profile or
/// recipe, or a method that isn't the user's.
fn not_restorable(store: RowStore, id: &str) -> bool {
    match store {
        RowStore::Profiles => is_builtin_profile(id),
        RowStore::Recipes => is_builtin_recipe(id),
        RowStore::CustomMethods => !is_custom_method_id(id),
        _ => false,
    }
}

fn row_plan(
    m: &BackupManifest,
    local: &LocalState,
    mode: RestoreMode,
    store: RowStore,
) -> StorePlan {
    let here: HashSet<&str> = local.ids(store).into_iter().collect();
    let backup: Vec<&str> = m
        .ids(store)
        .into_iter()
        .filter(|id| !not_restorable(store, id))
        .collect();
    let in_backup: HashSet<&str> = backup.iter().copied().collect();
    let mut plan = StorePlan::default();
    for id in &backup {
        if !here.contains(id) {
            plan.add.push((*id).to_owned());
        } else if mode == RestoreMode::Replace {
            plan.overwrite.push((*id).to_owned());
        } else {
            plan.keep += 1;
        }
    }
    if mode == RestoreMode::Replace {
        plan.remove = local
            .ids(store)
            .into_iter()
            .filter(|id| !in_backup.contains(id))
            .map(str::to_owned)
            .collect();
    }
    if mode == RestoreMode::Replace && !m.carries(store) {
        // Nothing to match: what's here stays.
        plan = StorePlan {
            keep: u32::try_from(here.len()).unwrap_or(u32::MAX),
            ..StorePlan::default()
        };
    }
    plan
}

fn string_list(value: Option<&serde_json::Value>, key: &str) -> Vec<String> {
    value
        .and_then(|v| v.get(key))
        .and_then(serde_json::Value::as_array)
        .map(|a| {
            a.iter()
                .filter_map(serde_json::Value::as_str)
                .map(str::to_owned)
                .collect()
        })
        .unwrap_or_default()
}

/// `a` then what `b` adds, once each.
fn union(a: &[String], b: &[String]) -> Vec<String> {
    let mut out: Vec<String> = Vec::with_capacity(a.len() + b.len());
    for id in a.iter().chain(b) {
        if !out.contains(id) {
            out.push(id.clone());
        }
    }
    out
}

/// Plan one restore of the backup `m` over what's `local`, in `mode`. See the
/// module docs, and the design's section 3 for each store's rule.
#[must_use]
pub fn plan_restore(
    m: &BackupManifest,
    local: &LocalState,
    mode: RestoreMode,
    opts: RestoreOpts,
) -> RestorePlan {
    let replace = mode == RestoreMode::Replace;
    let mut refusals = Vec::new();
    let mut warnings = Vec::new();

    // ── Refusals ──
    if m.shots_export {
        refusals.push(PlanNote::new(PlanCode::ShotsExport));
    } else if m.newer_format {
        refusals.push(PlanNote::new(PlanCode::NewerFormat));
    } else if !m.is_backup() {
        refusals.push(PlanNote::new(PlanCode::NotABackup));
    }
    for h in &local.health {
        match h.health {
            LocalHealth::Unavailable => {
                refusals.push(PlanNote::new(PlanCode::StoreUnavailable).store(h.store.clone()));
            }
            LocalHealth::Loading => {
                refusals.push(PlanNote::new(PlanCode::StoreLoading).store(h.store.clone()))
            }
            LocalHealth::Ok | LocalHealth::Absent | LocalHealth::Damaged => {}
        }
    }
    if local.machine_busy {
        refusals.push(PlanNote::new(PlanCode::MachineBusy));
    }
    let row_bad_lines = m.bad_lines.saturating_sub(u32::from(m.header_damaged));
    if m.header_damaged && !m.shots_export {
        let note = PlanNote::new(PlanCode::HeaderDamaged);
        if replace {
            refusals.push(note);
        } else {
            warnings.push(note);
        }
    }
    if row_bad_lines > 0 {
        let note = PlanNote::new(PlanCode::BadLines).count32(row_bad_lines);
        if replace {
            refusals.push(note);
        } else {
            warnings.push(note);
        }
    }
    for c in &m.count_mismatches {
        let note = PlanNote::new(PlanCode::CountMismatch)
            .count32(c.lines)
            .store(c.store.name());
        if replace {
            refusals.push(note);
        } else {
            warnings.push(note);
        }
    }

    // ── Rows ──
    let beans = row_plan(m, local, mode, RowStore::Beans);
    let roasters = row_plan(m, local, mode, RowStore::Roasters);
    let profiles = row_plan(m, local, mode, RowStore::Profiles);
    let recipes = row_plan(m, local, mode, RowStore::Recipes);
    let custom_methods = row_plan(m, local, mode, RowStore::CustomMethods);
    let (shots, tombstones) = shot_plan(m, local, mode, opts, &mut refusals, &mut warnings);

    if replace && m.counted_rows() == 0 {
        let has_rows = RowStore::ALL.iter().any(|s| !local.ids(*s).is_empty());
        if has_rows {
            refusals.push(PlanNote::new(PlanCode::EmptyBackup));
        }
    }
    let omitted = m.header.as_ref().map_or(0, |h| h.omitted_count);
    if omitted > 0 {
        warnings.push(PlanNote::new(PlanCode::Omitted).count32(omitted));
    }
    if m.unknown_kinds > 0 {
        warnings.push(
            PlanNote::new(PlanCode::UnknownKinds)
                .count32(m.unknown_kinds)
                .noted(),
        );
    }
    if m.duplicates > 0 {
        warnings.push(
            PlanNote::new(PlanCode::Duplicates)
                .count32(m.duplicates)
                .noted(),
        );
    }

    // ── Photos ──
    let photos = photo_plan(m, local, mode, &beans);

    // ── Settings-like lines ──
    let pristine = common_is_pristine(&local.common);
    let settings = match &m.settings {
        None => SettingsPlan::default(),
        Some(line) => {
            let common = if replace || pristine {
                PlanAction::Apply
            } else {
                PlanAction::Keep
            };
            // A settings line from before `_shell` was Android's: only Android
            // wrote one without it.
            let same_shell = line.shell.as_deref().unwrap_or("android") == local.shell;
            SettingsPlan {
                common,
                extras: if common == PlanAction::Apply && same_shell {
                    PlanAction::Apply
                } else {
                    PlanAction::Keep
                },
            }
        }
    };
    let maintenance = if m.has_maintenance && (replace || local.total_litres == 0.0) {
        PlanAction::Apply
    } else {
        PlanAction::Keep
    };
    let visualizer_prefs = if m.has_visualizer_prefs
        && (replace || local.visualizer_prefs == VisualizerSyncPrefs::default())
    {
        PlanAction::Apply
    } else {
        PlanAction::Keep
    };
    let profile_meta = profile_meta_plan(m.profile_meta.as_ref(), &local.profile_meta, mode);
    let recipe_meta = recipe_meta_plan(m.recipe_meta.as_ref(), &local.recipe_meta, mode);
    let clear_active_bean = replace
        && m.carries(RowStore::Beans)
        && local
            .active_bean_id
            .as_ref()
            .is_some_and(|id| beans.remove.contains(id));

    let verdict = if !refusals.is_empty() {
        Verdict::Refused
    } else if replace || warnings.iter().any(|w| w.confirm) {
        Verdict::Confirm
    } else {
        Verdict::Ok
    };
    let source = m
        .header
        .as_ref()
        .map(|h| PlanSource {
            created_at: h.created_at,
            app_version: h.app_version.clone(),
            device_label: h.device_label.clone(),
            omitted_count: h.omitted_count,
        })
        .unwrap_or_default();
    RestorePlan {
        mode,
        verdict,
        refusals,
        warnings,
        source,
        beans,
        roasters,
        profiles,
        recipes,
        custom_methods,
        shots,
        tombstones,
        photos,
        settings,
        maintenance,
        visualizer_prefs,
        profile_meta,
        recipe_meta,
        clear_active_bean,
    }
}

fn shot_plan(
    m: &BackupManifest,
    local: &LocalState,
    mode: RestoreMode,
    opts: RestoreOpts,
    refusals: &mut Vec<PlanNote>,
    warnings: &mut Vec<PlanNote>,
) -> (ShotPlan, TombstonePlan) {
    let replace = mode == RestoreMode::Replace;
    let here: HashMap<&str, &LocalShot> = local.shots.iter().map(|s| (s.id.as_str(), s)).collect();
    let added: HashSet<&str> = local.added_beans.iter().map(String::as_str).collect();
    let beans_here: HashSet<&str> = local
        .beans
        .iter()
        .map(String::as_str)
        .filter(|b| !added.contains(b))
        .collect();
    let mut plan = ShotPlan::default();
    let mut tombstones = TombstonePlan::default();
    let mut candidates: Vec<&ManifestShot> = Vec::new();
    for shot in &m.shots {
        match here.get(shot.id.as_str()) {
            Some(l) if l.deleted => {
                if replace || opts.revive_deleted {
                    tombstones.cancel.push(shot.id.clone());
                    if replace {
                        plan.revive.push(shot.id.clone());
                    } else {
                        candidates.push(shot);
                    }
                } else {
                    plan.deleted_here.push(shot.id.clone());
                }
            }
            Some(l) => {
                if replace {
                    plan.overwrite.push(shot.id.clone());
                } else {
                    plan.keep += 1;
                    if !l.has_samples && shot.has_samples {
                        plan.fill.push(shot.id.clone());
                    }
                }
            }
            None if replace => plan.add.push(shot.id.clone()),
            None => candidates.push(shot),
        }
    }
    if replace {
        if m.carries(RowStore::Shots) {
            let in_backup: HashSet<&str> = m.shots.iter().map(|s| s.id.as_str()).collect();
            for l in local.shots.iter().filter(|s| !s.deleted) {
                if !in_backup.contains(l.id.as_str()) {
                    plan.remove.push(l.id.clone());
                    if l.pinned {
                        plan.pinned_removed += 1;
                    }
                }
            }
            if plan.pinned_removed > 0 {
                warnings.push(PlanNote::new(PlanCode::PinnedRemoved).count32(plan.pinned_removed));
            }
            if let Some(cap) = local.history_capacity {
                let unpinned = m.shots.iter().filter(|s| !s.pinned).count();
                if u32::try_from(unpinned).map_or(true, |n| n > cap) {
                    refusals.push(PlanNote::new(PlanCode::OverCapacity).count(unpinned));
                }
            }
        } else {
            plan = ShotPlan {
                keep: u32::try_from(local.shots.iter().filter(|s| !s.deleted).count())
                    .unwrap_or(u32::MAX),
                ..ShotPlan::default()
            };
            tombstones.cancel.clear();
        }
        return (plan, tombstones);
    }

    // Merge never evicts. The cap counts unpinned rows only, as history keeps
    // them (`fits_history`): a pinned row always fits, and the newest unpinned
    // ones fill the room the device's unpinned rows leave.
    let room = local.history_capacity.map_or(usize::MAX, |cap| {
        let live = local
            .shots
            .iter()
            .filter(|s| !s.deleted && !s.pinned)
            .count();
        usize::try_from(cap)
            .unwrap_or(usize::MAX)
            .saturating_sub(live)
    });
    candidates.sort_by(|a, b| {
        b.pinned
            .cmp(&a.pinned)
            .then(b.completed_at.cmp(&a.completed_at))
    });
    let pinned = candidates.iter().filter(|s| s.pinned).count();
    let fits = pinned + (candidates.len() - pinned).min(room);
    let (added, left) = candidates.split_at(fits);
    plan.not_added = u32::try_from(left.len()).unwrap_or(u32::MAX);
    let left: HashSet<&str> = left.iter().map(|s| s.id.as_str()).collect();
    // Keep file order for what's added: a cap decides which, not the order.
    for shot in &m.shots {
        if !added.iter().any(|a| a.id == shot.id) {
            if left.contains(shot.id.as_str()) && here.contains_key(shot.id.as_str()) {
                plan.not_revived.push(shot.id.clone());
            }
            continue;
        }
        if here.contains_key(shot.id.as_str()) {
            // A tombstone here, which the backup's row replaces.
            plan.revive.push(shot.id.clone());
        } else {
            plan.add.push(shot.id.clone());
            if shot
                .bean_id
                .as_deref()
                .is_some_and(|b| beans_here.contains(b))
            {
                plan.zero_debit.push(shot.id.clone());
            }
        }
    }
    // A revived shot that doesn't fit keeps its tombstone.
    tombstones.cancel.retain(|id| !left.contains(id.as_str()));
    if plan.not_added > 0 {
        warnings.push(PlanNote::new(PlanCode::NotAdded).count32(plan.not_added));
    }
    if !plan.deleted_here.is_empty() {
        warnings.push(
            PlanNote::new(PlanCode::DeletedHere)
                .count(plan.deleted_here.len())
                .noted(),
        );
    }
    (plan, tombstones)
}

fn photo_plan(
    m: &BackupManifest,
    local: &LocalState,
    mode: RestoreMode,
    beans: &StorePlan,
) -> PhotoPlan {
    let in_backup: HashSet<&str> = m.photos.iter().map(String::as_str).collect();
    let mut plan = PhotoPlan::default();
    match mode {
        RestoreMode::Merge => {
            let added: HashSet<&str> = beans.add.iter().map(String::as_str).collect();
            let missing: HashSet<&str> = local
                .beans_missing_photo
                .iter()
                .map(String::as_str)
                .collect();
            plan.put = m
                .photos
                .iter()
                .filter(|p| added.contains(p.as_str()) || missing.contains(p.as_str()))
                .cloned()
                .collect();
        }
        RestoreMode::Replace => {
            plan.put = m.photos.clone();
            let removed: HashSet<&str> = beans.remove.iter().map(String::as_str).collect();
            plan.remove = local
                .photos
                .iter()
                .filter(|p| removed.contains(p.as_str()) && !in_backup.contains(p.as_str()))
                .cloned()
                .collect();
        }
    }
    plan
}

fn profile_meta_plan(
    backup: Option<&serde_json::Value>,
    local: &ProfileMeta,
    mode: RestoreMode,
) -> ProfileMetaPlan {
    let Some(backup) = backup else {
        return ProfileMetaPlan::default();
    };
    let pinned = string_list(Some(backup), "pinned");
    let hidden = string_list(Some(backup), "hiddenBuiltins");
    let (pinned, hidden_builtins) = match mode {
        RestoreMode::Replace => (pinned, hidden),
        RestoreMode::Merge => (
            union(&local.pinned, &pinned),
            union(&local.hidden_builtins, &hidden),
        ),
    };
    if mode == RestoreMode::Merge
        && pinned == local.pinned
        && hidden_builtins == local.hidden_builtins
    {
        return ProfileMetaPlan::default();
    }
    ProfileMetaPlan {
        action: PlanAction::Apply,
        pinned,
        hidden_builtins,
    }
}

/// A map written in key order: a hash map's own order differs from one map to
/// the next.
fn ordered<S: serde::Serializer>(map: &HashMap<String, String>, s: S) -> Result<S::Ok, S::Error> {
    s.collect_map(map.iter().collect::<BTreeMap<_, _>>())
}

fn recipe_meta_plan(
    backup: Option<&serde_json::Value>,
    local: &RecipeMeta,
    mode: RestoreMode,
) -> RecipeMetaPlan {
    let Some(backup) = backup else {
        return RecipeMetaPlan::default();
    };
    let theirs: HashMap<String, String> = backup
        .get("defaults")
        .and_then(serde_json::Value::as_object)
        .map(|o| {
            o.iter()
                .filter_map(|(k, v)| v.as_str().map(|v| (k.clone(), v.to_owned())))
                .collect()
        })
        .unwrap_or_default();
    let hidden = string_list(Some(backup), "hiddenBuiltins");
    let (defaults, hidden_builtins) = match mode {
        RestoreMode::Replace => (theirs, hidden),
        RestoreMode::Merge => {
            // A pointer here wins; the backup fills the gaps.
            let mut defaults = theirs;
            defaults.extend(local.defaults.iter().map(|(k, v)| (k.clone(), v.clone())));
            (defaults, union(&local.hidden_builtins, &hidden))
        }
    };
    if mode == RestoreMode::Merge
        && defaults == local.defaults
        && hidden_builtins == local.hidden_builtins
    {
        return RecipeMetaPlan::default();
    }
    RecipeMetaPlan {
        action: PlanAction::Apply,
        defaults,
        hidden_builtins,
    }
}

// ── JSON, for the shells ───────────────────────────────────────────────────

/// [`scan_backup_bytes`], with the bean ids the zip holds photos for, as a
/// [`BackupManifest`] JSON.
#[must_use]
pub fn scan_backup_bytes_json(bytes: &[u8], photos: &[String]) -> String {
    let mut manifest = scan_backup_bytes(bytes);
    manifest.photos = photos.to_vec();
    serde_json::to_string(&manifest).unwrap_or_default()
}

/// [`import_backup_bytes`] as a `BackupImportPlan` JSON.
#[must_use]
pub fn import_backup_bytes_json(bytes: &[u8]) -> String {
    serde_json::to_string(&import_backup_bytes(bytes)).unwrap_or_default()
}

/// [`plan_restore`] over JSON: a [`BackupManifest`], a [`LocalState`], the
/// mode and the [`RestoreOpts`], to a [`RestorePlan`].
///
/// # Errors
///
/// The parse error, when an input isn't what it should be.
pub fn plan_restore_json(
    manifest_json: &str,
    local_json: &str,
    mode: RestoreMode,
    opts_json: &str,
) -> Result<String, String> {
    let manifest: BackupManifest =
        serde_json::from_str(manifest_json).map_err(|e| e.to_string())?;
    let local: LocalState = serde_json::from_str(local_json).map_err(|e| e.to_string())?;
    let opts: RestoreOpts = serde_json::from_str(opts_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&plan_restore(&manifest, &local, mode, opts)).map_err(|e| e.to_string())
}

/// [`common_is_pristine`] over a `CommonSettings` JSON. One that won't read is
/// not pristine: Merge then keeps what's here.
#[must_use]
pub fn common_is_pristine_json(common_json: &str) -> bool {
    serde_json::from_str::<CommonSettings>(common_json).is_ok_and(|c| common_is_pristine(&c))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A shell compares the plan the user confirmed with a fresh one by its
    /// JSON, so the same inputs must serialise the same way every time. With
    /// a hash map, the method order changed from one map to the next, and the
    /// web restore matrix's "recipe meta: merge keeps a pointer here" came back
    /// `changed` instead of `done`.
    #[test]
    fn a_recipe_meta_plan_serialises_the_same_way_every_time() {
        let local = RecipeMeta {
            defaults: [("pourover", "recipe:mine")]
                .into_iter()
                .map(|(k, v)| (k.to_owned(), v.to_owned()))
                .collect(),
            hidden_builtins: Vec::new(),
        };
        let backup = serde_json::json!({
            "defaults": {
                "pourover": "recipe:theirs", "moka": "recipe:moka", "v60": "recipe:a",
                "aeropress": "recipe:b", "chemex": "recipe:c", "clever": "recipe:d"
            },
            "hiddenBuiltins": []
        });
        let plan = || {
            serde_json::to_string(&recipe_meta_plan(Some(&backup), &local, RestoreMode::Merge))
                .unwrap()
        };
        let first = plan();
        for _ in 0..50 {
            assert_eq!(plan(), first);
        }
        let at = |k: &str| first.find(k).unwrap();
        assert!(
            at("aeropress") < at("chemex") && at("chemex") < at("v60"),
            "{first}"
        );
        assert!(
            first.contains(r#""pourover":"recipe:mine""#),
            "a pointer here wins"
        );
    }
}

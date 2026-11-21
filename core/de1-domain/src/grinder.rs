//! # grinder
//!
//! Owned grinders and their grind scales (#488, phase 1).
//!
//! The grinder is equipment the user picks on Brew, not a field on the bag.
//! Settings hold the **owned grinders** ([`CommonSettings::grinders`]), each
//! with a [`GrindScale`], and which one is active
//! ([`CommonSettings::active_grinder_id`]). The core answers:
//!
//! - **The active scale** ([`active_grind_scale`]): the active grinder's, else
//!   [`GrindScale::default`] (0–20, step 0.1, no unit: the range before #488).
//!   The Quick Controls grind range follows it
//!   ([`crate::bounds::quick_control_bounds_for`]).
//! - **Presets** ([`GRINDER_PRESETS`]): common grinders with a single numbered
//!   dial, each range checked against the maker's manual or product page
//!   (the source is next to each entry). Names match case-insensitively, with
//!   aliases ("Niche" is the Niche Zero). Composite dials (macro plus micro,
//!   rotation.number.click) have no preset.
//! - **The grind on selecting a bag or grinder** ([`grind_on_select`]): the
//!   newest shot on that bag with that grinder, else the bag's old stored
//!   setting when the bag's old grinder is the active one, else no change.
//! - **The migration** ([`migrate_grinders`]): one owned grinder per name the
//!   old fields carry. It reads `CommonSettings::grinder_model`, `Bean::grinder`
//!   and `Bean::grinder_setting` and never writes them: they stay in storage,
//!   import, export and backups as they were.
//!
//! **Grind values are never clamped or rewritten.** A scale is only the
//! stepper's range: a stored or typed value outside it is kept as it is.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::app_settings::CommonSettings;
use crate::hot_water::StepRange;

/// The scale with no active grinder: the Quick Controls grind range before
/// #488.
pub const DEFAULT_GRIND_MIN: f32 = 0.0;
/// See [`DEFAULT_GRIND_MIN`].
pub const DEFAULT_GRIND_MAX: f32 = 20.0;
/// See [`DEFAULT_GRIND_MIN`].
pub const DEFAULT_GRIND_STEP: f32 = 0.1;

/// A grinder's grind scale: the stepper's range and the unit shown next to a
/// value. `unit` is free text and may be empty ("μm", "clicks", "turns", …).
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct GrindScale {
    /// Lowest setting.
    pub min: f32,
    /// Highest setting.
    pub max: f32,
    /// Step between settings.
    pub step: f32,
    /// The unit label; empty for none.
    pub unit: String,
}

impl Default for GrindScale {
    fn default() -> Self {
        Self {
            min: DEFAULT_GRIND_MIN,
            max: DEFAULT_GRIND_MAX,
            step: DEFAULT_GRIND_STEP,
            unit: String::new(),
        }
    }
}

impl GrindScale {
    /// The stepper range: this scale, repaired so a stepper can use it. A
    /// non-finite bound or step takes the default's, a step that isn't
    /// positive takes the default step, and swapped bounds are put in order.
    /// The stored scale is not changed.
    #[must_use]
    pub fn step_range(&self) -> StepRange {
        let d = Self::default();
        let finite_or = |v: f32, fallback: f32| if v.is_finite() { v } else { fallback };
        let a = finite_or(self.min, d.min);
        let b = finite_or(self.max, d.max);
        let step = if self.step.is_finite() && self.step > 0.0 {
            self.step
        } else {
            d.step
        };
        StepRange {
            min: a.min(b),
            max: a.max(b),
            step,
            chips: Vec::new(),
        }
    }

    /// Whether `value` lies inside the scale: the shells warn on a typed value
    /// outside it, and keep the value.
    #[must_use]
    pub fn contains(&self, value: f32) -> bool {
        self.step_range().contains(value)
    }
}

/// A grinder the user owns.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct OwnedGrinder {
    /// Stable id, unique among the owned grinders ([`add_grinder`] mints it).
    pub id: String,
    /// The name shots are stamped with: `"Niche Zero"`.
    pub name: String,
    /// Its grind scale; the user may change any of it.
    pub scale: GrindScale,
    /// The [`GrinderPreset::id`] the scale came from, if any.
    pub preset_id: Option<String>,
}

/// A built-in grinder: its name, the names people type for it, its scale and
/// where the range comes from.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct GrinderPreset {
    /// Stable id, stored in [`OwnedGrinder::preset_id`].
    pub id: &'static str,
    /// Display name.
    pub name: &'static str,
    /// Other names that pick this preset, matched like [`Self::name`].
    pub aliases: &'static [&'static str],
    /// Lowest setting.
    pub min: f32,
    /// Highest setting.
    pub max: f32,
    /// Step between settings.
    pub step: f32,
    /// Unit label; empty for none.
    pub unit: &'static str,
    /// The maker's page the range was checked against.
    pub source: &'static str,
}

impl GrinderPreset {
    /// The preset's scale.
    #[must_use]
    pub fn scale(&self) -> GrindScale {
        GrindScale {
            min: self.min,
            max: self.max,
            step: self.step,
            unit: self.unit.to_owned(),
        }
    }

    /// Whether `name` names this preset: its name or an alias, ignoring case
    /// and surrounding spaces.
    #[must_use]
    pub fn matches(&self, name: &str) -> bool {
        let key = grinder_key(name);
        !key.is_empty()
            && (grinder_key(self.name) == key || self.aliases.iter().any(|a| grinder_key(a) == key))
    }
}

/// The built-in presets. Each range was checked against the maker's manual or
/// product page (`source`); a grinder whose range couldn't be checked there
/// (DF64, DF83, Eureka Mignon, EK43, Timemore Sculptor, Comandante, Kingrinder,
/// Lagom P64, Zerno, Varia VS3), or whose dial is composite (Baratza Vario
/// and Sette 270, 1Zpresso, Weber EG-1, Fellow Opus with its inner ring), is
/// left out.
pub const GRINDER_PRESETS: &[GrinderPreset] = &[
    // `bladeGap`, the grind size in μm, 0–999.
    GrinderPreset {
        id: "bookoo-motto-80",
        name: "BOOKOO Motto 80",
        aliases: &["Motto 80", "Motto80", "BOOKOO MT80", "MT80"],
        min: 0.0,
        max: 999.0,
        step: 1.0,
        unit: "μm",
        source: "https://github.com/BooKooCode/OpenSource/tree/main/bookoo_motto80",
    },
    // The manual's dial drawing runs 0 to 50; stepless, whole-number marks.
    GrinderPreset {
        id: "niche-zero",
        name: "Niche Zero",
        aliases: &["Niche", "NZ"],
        min: 0.0,
        max: 50.0,
        step: 1.0,
        unit: "",
        source: "https://cdn.shopify.com/s/files/1/0081/2076/2450/files/Niche_Zero_IFU.pdf",
    },
    // '0' is where the burrs touch; the dial drawing runs to 70, one tick per unit.
    GrinderPreset {
        id: "niche-duo",
        name: "Niche Duo",
        aliases: &["Niche Duo grinder"],
        min: 0.0,
        max: 70.0,
        step: 1.0,
        unit: "",
        source: "https://cdn.shopify.com/s/files/1/0081/2076/2450/files/N685_INSTRUCTION_BOOK_ROW_83F_1_080323_WEB.pdf",
    },
    // "from very coarse (grind setting 40) to very fine (grind setting 1)".
    GrinderPreset {
        id: "baratza-encore",
        name: "Baratza Encore",
        aliases: &["Encore"],
        min: 1.0,
        max: 40.0,
        step: 1.0,
        unit: "",
        source: "https://breville-production-aem-assets.s3.us-west-2.amazonaws.com/ZCG485/Encore+Documents/manual-encore-en-v4-3-120122-1.pdf",
    },
    // "the espresso range (settings 1-20) and the filter coffee range (settings 21-40)".
    GrinderPreset {
        id: "baratza-encore-esp",
        name: "Baratza Encore ESP",
        aliases: &["Encore ESP"],
        min: 1.0,
        max: 40.0,
        step: 1.0,
        unit: "",
        source: "https://breville-production-aem-assets.s3.us-west-2.amazonaws.com/ZCG495/Encore+ESP+Documents/manual-encoreesp-v1-0-en-010923.pdf",
    },
    // "from very coarse (grind setting 40) to very fine (grind setting 1)".
    GrinderPreset {
        id: "baratza-virtuoso-plus",
        name: "Baratza Virtuoso+",
        aliases: &[
            "Virtuoso+",
            "Virtuoso",
            "Virtuoso Plus",
            "Baratza Virtuoso Plus",
        ],
        min: 1.0,
        max: 40.0,
        step: 1.0,
        unit: "",
        source: "https://breville-production-aem-assets.s3.us-west-2.amazonaws.com/ZCG587/Virtuoso%2B+Documents/manual-virtuosoplus-en-v1-6.pdf",
    },
    // "The adjustment ring moves from coarse (31) to fine (1)"; macro ring only (the Sette 270 is composite: no preset).
    GrinderPreset {
        id: "baratza-sette-30",
        name: "Baratza Sette 30",
        aliases: &["Sette 30"],
        min: 1.0,
        max: 31.0,
        step: 1.0,
        unit: "",
        source: "https://breville-production-aem-assets.s3.us-west-2.amazonaws.com/ZCG1130/Sette+30+Documents/manual-sette30-en-v3.pdf",
    },
    // "60 custom grind settings"; the inner upper-burr setting only recalibrates.
    GrinderPreset {
        id: "breville-smart-grinder-pro",
        name: "Breville Smart Grinder Pro",
        aliases: &[
            "Smart Grinder Pro",
            "Sage Smart Grinder Pro",
            "SGP",
            "BCG820",
        ],
        min: 1.0,
        max: 60.0,
        step: 1.0,
        unit: "",
        source: "https://assets.breville.com/BCG820/BCG820_USCM_IB_L26_LR.pdf",
    },
    // "60 grind settings, from espresso to plunger".
    GrinderPreset {
        id: "breville-dose-control-pro",
        name: "Breville Dose Control Pro",
        aliases: &["Dose Control Pro", "Sage Dose Control Pro", "BCG600"],
        min: 1.0,
        max: 60.0,
        step: 1.0,
        unit: "",
        source: "https://assets.breville.com/Instruction-Booklets/ANZ/BCG600_instruction_manual.pdf",
    },
    // Stepless; the scale's finest setting is "01", the coarsest "35".
    GrinderPreset {
        id: "mahlkonig-x54",
        name: "Mahlkönig X54",
        aliases: &["X54", "Mahlkonig X54", "Mahlkoenig X54"],
        min: 1.0,
        max: 35.0,
        step: 1.0,
        unit: "",
        source: "https://downloads.mahlkoenig.de/Service/Mahlkoenig_X54_Allround_Home_Grinder_Original_instruction_manual_EN.pdf",
    },
];

/// A preset as the shells list it.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GrinderPresetInfo {
    /// [`GrinderPreset::id`].
    pub id: String,
    /// Display name.
    pub name: String,
    /// Other names that pick it.
    pub aliases: Vec<String>,
    /// Its scale.
    pub scale: GrindScale,
    /// The maker's page the range was checked against.
    pub source: String,
}

impl From<&GrinderPreset> for GrinderPresetInfo {
    fn from(p: &GrinderPreset) -> Self {
        Self {
            id: p.id.to_owned(),
            name: p.name.to_owned(),
            aliases: p.aliases.iter().map(|a| (*a).to_owned()).collect(),
            scale: p.scale(),
            source: p.source.to_owned(),
        }
    }
}

/// The presets, for the shells' "Add grinder" list.
#[must_use]
pub fn grinder_presets() -> Vec<GrinderPresetInfo> {
    GRINDER_PRESETS
        .iter()
        .map(GrinderPresetInfo::from)
        .collect()
}

/// [`grinder_presets`] as JSON.
#[must_use]
pub fn grinder_presets_json() -> String {
    serde_json::to_string(&grinder_presets()).unwrap_or_default()
}

/// The preset `name` names (its name or an alias, ignoring case), if any.
#[must_use]
pub fn preset_for_name(name: &str) -> Option<&'static GrinderPreset> {
    GRINDER_PRESETS.iter().find(|p| p.matches(name))
}

/// The preset with this id, if any.
#[must_use]
pub fn preset_by_id(id: &str) -> Option<&'static GrinderPreset> {
    GRINDER_PRESETS.iter().find(|p| p.id == id)
}

/// What two grinder names are compared by: trimmed and lowercased. Empty for
/// a blank name, which names no grinder.
#[must_use]
pub fn grinder_key(name: &str) -> String {
    name.trim().to_lowercase()
}

/// Whether `a` and `b` name the same grinder (ignoring case and surrounding
/// spaces). A blank name matches nothing.
#[must_use]
pub fn same_grinder(a: &str, b: &str) -> bool {
    let a = grinder_key(a);
    !a.is_empty() && a == grinder_key(b)
}

/// The active grinder: the owned grinder whose id is
/// [`CommonSettings::active_grinder_id`]. `None` when none is set or the id
/// names no owned grinder.
#[must_use]
pub fn active_grinder(settings: &CommonSettings) -> Option<&OwnedGrinder> {
    let id = settings.active_grinder_id.as_deref()?;
    settings.grinders.as_deref()?.iter().find(|g| g.id == id)
}

/// The active grinder's scale, else [`GrindScale::default`].
#[must_use]
pub fn active_grind_scale(settings: &CommonSettings) -> GrindScale {
    active_grinder(settings).map_or_else(GrindScale::default, |g| g.scale.clone())
}

/// The active grinder's name, trimmed, for stamping a shot
/// ([`crate::ShotCaptureContext::settings_grinder`]). `None` with no active
/// grinder or a blank name.
#[must_use]
pub fn active_grinder_name(settings: &CommonSettings) -> Option<String> {
    active_grinder(settings)
        .map(|g| g.name.trim())
        .filter(|n| !n.is_empty())
        .map(str::to_owned)
}

/// An id for a new grinder called `name`, unique among `taken`: `grinder:`
/// and the name's lowercase letters and digits joined by `-`, with `-2`,
/// `-3`, … when that is taken. Deterministic, so the migration gives the
/// same ids each time it runs on the same data.
fn mint_id(name: &str, taken: &[OwnedGrinder]) -> String {
    let mut slug = String::new();
    for c in name.trim().chars().flat_map(char::to_lowercase) {
        if c.is_alphanumeric() {
            slug.push(c);
        } else if !slug.is_empty() && !slug.ends_with('-') {
            slug.push('-');
        }
    }
    let slug = slug.trim_end_matches('-');
    let base = if slug.is_empty() {
        "grinder:grinder".to_owned()
    } else {
        format!("grinder:{slug}")
    };
    let free = |id: &str| !taken.iter().any(|g| g.id == id);
    if free(&base) {
        return base;
    }
    (2_u32..)
        .map(|n| format!("{base}-{n}"))
        .find(|id| free(id))
        .unwrap_or(base)
}

/// A new owned grinder called `name` (trimmed): the preset's scale when
/// `preset_id` names one, else the scale of the preset the name matches, else
/// the default scale.
fn new_grinder(name: &str, preset_id: Option<&str>, taken: &[OwnedGrinder]) -> OwnedGrinder {
    let preset = preset_id
        .and_then(preset_by_id)
        .or_else(|| preset_for_name(name));
    OwnedGrinder {
        id: mint_id(name, taken),
        name: name.trim().to_owned(),
        scale: preset.map_or_else(GrindScale::default, GrinderPreset::scale),
        preset_id: preset.map(|p| p.id.to_owned()),
    }
}

/// `settings` with a new owned grinder called `name`, and its id. Its scale
/// is `preset_id`'s preset, else the preset the name matches, else the
/// default. The first grinder becomes the active one. A blank name adds
/// nothing and gives `None`. Settings not migrated yet are migrated first
/// (with no bags), so the settings grinder isn't skipped.
#[must_use]
pub fn add_grinder(
    settings: CommonSettings,
    name: &str,
    preset_id: Option<&str>,
) -> (CommonSettings, Option<String>) {
    if grinder_key(name).is_empty() {
        return (settings, None);
    }
    let mut settings = migrate_grinders::<&str>(settings, &[]);
    let grinders = settings.grinders.get_or_insert_with(Vec::new);
    let g = new_grinder(name, preset_id, grinders);
    let id = g.id.clone();
    grinders.push(g);
    if active_grinder(&settings).is_none() {
        settings.active_grinder_id = Some(id.clone());
    }
    (settings, Some(id))
}

/// `settings` without the owned grinder `id`. Shots and bags are not touched.
/// When it was the active one, the first remaining grinder becomes active
/// (none when none remain).
#[must_use]
pub fn remove_grinder(mut settings: CommonSettings, id: &str) -> CommonSettings {
    let Some(grinders) = settings.grinders.as_mut() else {
        return settings;
    };
    grinders.retain(|g| g.id != id);
    if settings.active_grinder_id.as_deref() == Some(id) {
        settings.active_grinder_id = grinders.first().map(|g| g.id.clone());
    }
    settings
}

/// `settings` with an owned grinder for each name in `names` that no owned
/// grinder has yet (trimmed, ignoring case; blank names skipped; the first
/// spelling of a repeated name is kept). For imports that carry a bag
/// grinder (Beanconqueror, Visualizer, backups). Idempotent. The first
/// grinder becomes the active one when none is.
///
/// Settings not migrated yet come back unchanged: the migration, which runs
/// on the next load, reads the imported bags along with the rest, and the
/// settings grinder first.
#[must_use]
pub fn add_grinders_for_names<S: AsRef<str>>(
    mut settings: CommonSettings,
    names: &[S],
) -> CommonSettings {
    let Some(grinders) = settings.grinders.as_mut() else {
        return settings;
    };
    add_names(grinders, names);
    if active_grinder(&settings).is_none() {
        settings.active_grinder_id = settings
            .grinders
            .as_deref()
            .and_then(<[OwnedGrinder]>::first)
            .map(|g| g.id.clone());
    }
    settings
}

/// Push an owned grinder for each new name (see [`add_grinders_for_names`]).
fn add_names<S: AsRef<str>>(grinders: &mut Vec<OwnedGrinder>, names: &[S]) {
    for name in names {
        let name = name.as_ref();
        if grinder_key(name).is_empty() || grinders.iter().any(|g| same_grinder(&g.name, name)) {
            continue;
        }
        let g = new_grinder(name, None, grinders);
        grinders.push(g);
    }
}

/// The one-time migration to owned grinders (#488). When `settings` has no
/// owned grinders yet ([`CommonSettings::grinders`] is `None`), it gets one
/// per distinct non-empty name among `grinder_model` then `bag_grinders` (the
/// bags' `Bean::grinder`, in library order): trimmed, de-duplicated ignoring
/// case, keeping the first spelling. Each takes the scale of the preset its
/// name matches, else the default scale. The active one is the one matching
/// `grinder_model`, else the first.
///
/// It never changes `grinder_model` or the bags: those stay stored as they
/// were. It runs once: settings that already have owned grinders (even an
/// empty list, after the user removed them all) come back unchanged.
#[must_use]
pub fn migrate_grinders<S: AsRef<str>>(
    mut settings: CommonSettings,
    bag_grinders: &[S],
) -> CommonSettings {
    if settings.grinders.is_some() {
        return settings;
    }
    let mut grinders = Vec::new();
    add_names(&mut grinders, &[settings.grinder_model.as_str()]);
    add_names(&mut grinders, bag_grinders);
    settings.active_grinder_id = grinders
        .iter()
        .find(|g| same_grinder(&g.name, &settings.grinder_model))
        .or_else(|| grinders.first())
        .map(|g| g.id.clone());
    settings.grinders = Some(grinders);
    settings
}

/// The bags' old grinder names from a bean-library JSON array, read
/// leniently: each element's `grinder` string, whatever else it holds. Not an
/// array: none.
fn bag_grinders_from_json(beans_json: &str) -> Vec<String> {
    let Ok(serde_json::Value::Array(beans)) = serde_json::from_str(beans_json) else {
        return Vec::new();
    };
    beans
        .iter()
        .filter_map(|b| b.get("grinder").and_then(serde_json::Value::as_str))
        .map(str::to_owned)
        .collect()
}

/// JSON-bridged [`migrate_grinders`]: a settings blob (read through
/// [`crate::app_settings::sanitize_common_settings`]) and the bean library (a
/// JSON array of `Bean`) in, the settings JSON out.
#[must_use]
pub fn migrate_grinders_json(settings_json: &str, beans_json: &str) -> String {
    let settings = settings_from_json(settings_json);
    let out = migrate_grinders(settings, &bag_grinders_from_json(beans_json));
    serde_json::to_string(&out).unwrap_or_default()
}

/// JSON-bridged [`add_grinders_for_names`]: a settings blob and the grinder
/// names an import carries (a JSON array of strings: imported bags'
/// `grinder`, imported shots' `grinderModel`) in, the settings JSON out.
/// Non-string elements are skipped.
#[must_use]
pub fn add_grinders_for_names_json(settings_json: &str, names_json: &str) -> String {
    let names: Vec<String> = match serde_json::from_str(names_json) {
        Ok(serde_json::Value::Array(names)) => names
            .iter()
            .filter_map(serde_json::Value::as_str)
            .map(str::to_owned)
            .collect(),
        _ => Vec::new(),
    };
    let out = add_grinders_for_names(settings_from_json(settings_json), &names);
    serde_json::to_string(&out).unwrap_or_default()
}

/// JSON-bridged [`active_grind_scale`]: a settings blob in, a [`GrindScale`]
/// JSON out.
#[must_use]
pub fn active_grind_scale_json(settings_json: &str) -> String {
    serde_json::to_string(&active_grind_scale(&settings_from_json(settings_json)))
        .unwrap_or_default()
}

/// A settings blob read through
/// [`crate::app_settings::sanitize_common_settings`]; malformed JSON gives the
/// defaults.
pub(crate) fn settings_from_json(settings_json: &str) -> CommonSettings {
    let blob = serde_json::from_str(settings_json).unwrap_or(serde_json::Value::Null);
    crate::app_settings::sanitize_common_settings(&blob)
}

/// The newest grind recorded on one bag with one grinder: what the history
/// answers per (bag, grinder) (`de1_history`'s `last_grinds_for_bag`).
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct LastGrind {
    /// The grinder the shot was stamped with, as spelled on the newest one.
    pub grinder: String,
    /// The grind it recorded, as stored.
    pub grind: String,
    /// When that shot completed, Unix epoch ms.
    #[typeshare(serialized_as = "I64")]
    pub completed_at: i64,
}

/// The newest grind for `grinder` among `last_grinds` (ignoring case), if any.
#[must_use]
pub fn last_grind_for<'a>(last_grinds: &'a [LastGrind], grinder: &str) -> Option<&'a LastGrind> {
    last_grinds
        .iter()
        .filter(|l| same_grinder(&l.grinder, grinder) && !l.grind.trim().is_empty())
        .max_by_key(|l| l.completed_at)
}

/// What [`grind_on_select`] reads.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct GrindOnSelectInput {
    /// The active grinder's name; `None` or blank when there is none.
    pub active_grinder: Option<String>,
    /// The bag's newest grind per grinder, from the history.
    pub last_grinds: Vec<LastGrind>,
    /// The bag's old stored grinder (`Bean::grinder`).
    pub bag_grinder: String,
    /// The bag's old stored setting (`Bean::grinder_setting`).
    pub bag_grinder_setting: String,
}

/// The grind Brew takes when a bag is selected or the grinder switched: the
/// newest shot's grind on that bag with the active grinder; else the bag's
/// old stored setting when its old stored grinder is the active grinder;
/// else `None`, which leaves the grind as it is. The value is returned as
/// stored, never clamped to the scale.
#[must_use]
pub fn grind_on_select(input: &GrindOnSelectInput) -> Option<String> {
    let active = input.active_grinder.as_deref().unwrap_or_default();
    if grinder_key(active).is_empty() {
        return None;
    }
    if let Some(last) = last_grind_for(&input.last_grinds, active) {
        return Some(last.grind.clone());
    }
    (same_grinder(&input.bag_grinder, active) && !input.bag_grinder_setting.trim().is_empty())
        .then(|| input.bag_grinder_setting.clone())
}

/// JSON-bridged [`grind_on_select`]: a [`GrindOnSelectInput`] JSON in, the
/// grind as a JSON string or `null` out. Malformed input gives `null`.
#[must_use]
pub fn grind_on_select_json(input_json: &str) -> String {
    let out = serde_json::from_str::<GrindOnSelectInput>(input_json)
        .ok()
        .and_then(|i| grind_on_select(&i));
    serde_json::to_string(&out).unwrap_or_else(|_| "null".to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::app_settings::{default_common_settings, reset_settings, sanitize_common_settings};
    use crate::bounds::{quick_control_bounds, quick_control_bounds_for_settings_json};
    use crate::{Bean, QuickControlBounds};

    fn settings(grinder_model: &str) -> CommonSettings {
        CommonSettings {
            grinder_model: grinder_model.to_owned(),
            ..default_common_settings()
        }
    }

    fn names(s: &CommonSettings) -> Vec<&str> {
        s.grinders
            .as_deref()
            .unwrap_or_default()
            .iter()
            .map(|g| g.name.as_str())
            .collect()
    }

    fn active_name(s: &CommonSettings) -> Option<&str> {
        active_grinder(s).map(|g| g.name.as_str())
    }

    fn owned(id: &str, name: &str) -> OwnedGrinder {
        OwnedGrinder {
            id: id.to_owned(),
            name: name.to_owned(),
            scale: GrindScale::default(),
            preset_id: None,
        }
    }

    // ── Presets ──

    #[test]
    fn every_preset_has_a_source_a_usable_scale_and_a_unique_id() {
        for p in GRINDER_PRESETS {
            assert!(p.source.starts_with("https://"), "{}", p.id);
            assert!(
                p.min.is_finite() && p.max.is_finite() && p.min < p.max,
                "{}",
                p.id
            );
            assert!(p.step > 0.0 && p.step <= p.max - p.min, "{}", p.id);
            assert_eq!(
                GRINDER_PRESETS.iter().filter(|q| q.id == p.id).count(),
                1,
                "{}",
                p.id
            );
        }
    }

    #[test]
    fn no_two_presets_answer_to_the_same_name() {
        for p in GRINDER_PRESETS {
            for name in std::iter::once(&p.name).chain(p.aliases) {
                let hits: Vec<&str> = GRINDER_PRESETS
                    .iter()
                    .filter(|q| q.matches(name))
                    .map(|q| q.id)
                    .collect();
                assert_eq!(hits, [p.id], "{name}");
            }
        }
    }

    #[test]
    fn the_motto_80_preset_is_0_to_999_microns_in_steps_of_1() {
        let p = preset_for_name("bookoo motto 80").expect("the Motto 80");
        assert_eq!(
            p.scale(),
            GrindScale {
                min: 0.0,
                max: 999.0,
                step: 1.0,
                unit: "μm".to_owned()
            }
        );
    }

    #[test]
    fn a_preset_matches_its_name_or_an_alias_ignoring_case_and_spaces() {
        assert_eq!(
            preset_for_name("  MOTTO 80 ").map(|p| p.id),
            Some("bookoo-motto-80")
        );
        assert_eq!(preset_for_name("niche").map(|p| p.id), Some("niche-zero"));
        assert_eq!(
            preset_for_name("Niche Duo").map(|p| p.id),
            Some("niche-duo")
        );
        assert_eq!(preset_for_name("Motto 80 Pro"), None);
        assert_eq!(preset_for_name("   "), None);
    }

    #[test]
    fn the_presets_list_as_json_with_their_sources() {
        let list: Vec<GrinderPresetInfo> = serde_json::from_str(&grinder_presets_json()).unwrap();
        assert_eq!(list.len(), GRINDER_PRESETS.len());
        assert!(list.iter().all(|p| !p.source.is_empty()));
    }

    // ── Scales and bounds ──

    #[test]
    fn the_default_scale_is_the_range_before_488() {
        let r = GrindScale::default().step_range();
        assert_eq!((r.min, r.max, r.step), (0.0, 20.0, 0.1));
        assert_eq!(quick_control_bounds().grind, r);
        assert_eq!(quick_control_bounds().grind_unit, "");
    }

    #[test]
    fn with_no_active_grinder_the_bounds_keep_the_default_scale() {
        let q: QuickControlBounds = serde_json::from_str(&quick_control_bounds_for_settings_json(
            &serde_json::to_string(&settings("")).unwrap(),
        ))
        .unwrap();
        assert_eq!(q, quick_control_bounds());
    }

    #[test]
    fn the_grind_bounds_follow_the_active_grinder() {
        let s = migrate_grinders::<&str>(settings("BOOKOO Motto 80"), &[]);
        let q: QuickControlBounds = serde_json::from_str(&quick_control_bounds_for_settings_json(
            &serde_json::to_string(&s).unwrap(),
        ))
        .unwrap();
        assert_eq!((q.grind.min, q.grind.max, q.grind.step), (0.0, 999.0, 1.0));
        assert_eq!(q.grind_unit, "μm");
        // Every other range is the plain table's.
        assert_eq!(
            QuickControlBounds {
                grind: quick_control_bounds().grind,
                grind_unit: String::new(),
                ..q
            },
            quick_control_bounds()
        );
    }

    #[test]
    fn a_dangling_active_id_is_no_active_grinder() {
        let s = CommonSettings {
            grinders: Some(vec![owned("grinder:a", "A")]),
            active_grinder_id: Some("grinder:gone".to_owned()),
            ..settings("")
        };
        assert!(active_grinder(&s).is_none());
        assert_eq!(active_grind_scale(&s), GrindScale::default());
        assert_eq!(active_grinder_name(&s), None);
    }

    #[test]
    fn a_broken_scale_is_repaired_for_the_stepper_but_not_in_storage() {
        let broken = GrindScale {
            min: 50.0,
            max: f32::NAN,
            step: -1.0,
            unit: "x".to_owned(),
        };
        let r = broken.step_range();
        assert_eq!((r.min, r.max, r.step), (20.0, 50.0, 0.1));
        assert!(broken.max.is_nan(), "the stored scale is untouched");
    }

    #[test]
    fn a_value_outside_the_scale_is_reported_not_clamped() {
        let s = preset_for_name("Motto 80").unwrap().scale();
        assert!(s.contains(500.0));
        assert!(!s.contains(1200.0));
        // Nothing here rewrites a value: grind_on_select hands it back as is.
        let input = GrindOnSelectInput {
            active_grinder: Some("Motto 80".to_owned()),
            last_grinds: vec![LastGrind {
                grinder: "Motto 80".to_owned(),
                grind: "1200".to_owned(),
                completed_at: 1,
            }],
            ..GrindOnSelectInput::default()
        };
        assert_eq!(grind_on_select(&input).as_deref(), Some("1200"));
    }

    // ── Migration (one test per rule) ──

    #[test]
    fn migration_creates_one_grinder_per_name_in_settings_then_bags() {
        let s = migrate_grinders(settings("EK43"), &["Niche Zero", "Comandante"]);
        assert_eq!(names(&s), ["EK43", "Niche Zero", "Comandante"]);
    }

    #[test]
    fn migration_skips_blank_names_and_trims_the_rest() {
        let s = migrate_grinders(settings("  "), &["", "  Niche Zero  ", "\t"]);
        assert_eq!(names(&s), ["Niche Zero"]);
    }

    #[test]
    fn migration_dedupes_ignoring_case_and_keeps_the_first_spelling() {
        let s = migrate_grinders(
            settings("niche zero"),
            &["Niche Zero", " NICHE ZERO", "EK43"],
        );
        assert_eq!(names(&s), ["niche zero", "EK43"]);
    }

    #[test]
    fn migration_takes_a_matching_presets_scale_else_the_default() {
        let s = migrate_grinders(settings("motto 80"), &["My Hand Grinder"]);
        let gs = s.grinders.as_deref().unwrap();
        assert_eq!(gs[0].scale, preset_for_name("Motto 80").unwrap().scale());
        assert_eq!(gs[0].preset_id.as_deref(), Some("bookoo-motto-80"));
        assert_eq!(gs[1].scale, GrindScale::default());
        assert_eq!(gs[1].preset_id, None);
    }

    #[test]
    fn migration_makes_the_settings_grinder_active() {
        let s = migrate_grinders(settings(" ek43 "), &["Niche Zero", "EK43"]);
        assert_eq!(names(&s), ["ek43", "Niche Zero"]);
        assert_eq!(active_name(&s), Some("ek43"));
    }

    #[test]
    fn migration_makes_the_first_bag_grinder_active_without_a_settings_grinder() {
        let s = migrate_grinders(settings(""), &["Niche Zero", "EK43"]);
        assert_eq!(active_name(&s), Some("Niche Zero"));
    }

    #[test]
    fn migration_with_no_names_owns_nothing_and_has_no_active_grinder() {
        let s = migrate_grinders::<&str>(settings(""), &[]);
        assert_eq!(s.grinders, Some(Vec::new()), "marked as migrated");
        assert_eq!(s.active_grinder_id, None);
        assert_eq!(active_grind_scale(&s), GrindScale::default());
    }

    #[test]
    fn migration_gives_distinct_stable_ids() {
        let a = migrate_grinders(settings("Niche Zero"), &["Niche-Zero", "EK43"]);
        let b = migrate_grinders(settings("Niche Zero"), &["Niche-Zero", "EK43"]);
        let ids: Vec<&str> = a
            .grinders
            .as_deref()
            .unwrap()
            .iter()
            .map(|g| g.id.as_str())
            .collect();
        assert_eq!(
            ids,
            ["grinder:niche-zero", "grinder:niche-zero-2", "grinder:ek43"]
        );
        assert_eq!(a, b, "deterministic");
    }

    #[test]
    fn migration_is_idempotent() {
        let once = migrate_grinders(settings("EK43"), &["Niche Zero"]);
        let twice = migrate_grinders(once.clone(), &["Niche Zero", "Comandante"]);
        assert_eq!(twice, once);
    }

    #[test]
    fn migration_runs_once_even_after_every_grinder_was_removed() {
        let s = migrate_grinders(settings("EK43"), &["Niche Zero"]);
        let ids: Vec<String> = s
            .grinders
            .as_deref()
            .unwrap()
            .iter()
            .map(|g| g.id.clone())
            .collect();
        let mut emptied = s;
        for id in &ids {
            emptied = remove_grinder(emptied, id);
        }
        assert_eq!(emptied.grinders, Some(Vec::new()));
        assert_eq!(migrate_grinders(emptied.clone(), &["Niche Zero"]), emptied);
    }

    #[test]
    fn migration_never_changes_the_old_fields() {
        let before = settings("  EK43 ");
        let after = migrate_grinders(before.clone(), &["Niche Zero"]);
        assert_eq!(after.grinder_model, "  EK43 ");
        assert_eq!(
            CommonSettings {
                grinders: None,
                active_grinder_id: None,
                ..after
            },
            before
        );
    }

    #[test]
    fn migration_reads_bags_from_a_bean_library_json() {
        let mut niche = Bean::new("bean:1".into(), "Gesha".into(), 1);
        niche.grinder = "Niche Zero".into();
        niche.grinder_setting = "14".into();
        let plain = Bean::new("bean:2".into(), "Kenya".into(), 1);
        let library = serde_json::to_string(&vec![niche.clone(), plain]).unwrap();
        let out: CommonSettings = serde_json::from_str(&migrate_grinders_json(
            &serde_json::to_string(&settings("")).unwrap(),
            &library,
        ))
        .unwrap();
        assert_eq!(names(&out), ["Niche Zero"]);
        // A library that isn't an array of objects migrates the settings
        // grinder alone.
        let out: CommonSettings = serde_json::from_str(&migrate_grinders_json(
            &serde_json::to_string(&settings("EK43")).unwrap(),
            "not json",
        ))
        .unwrap();
        assert_eq!(names(&out), ["EK43"]);
    }

    // ── Imports ──

    #[test]
    fn an_import_adds_only_new_grinder_names() {
        let s = migrate_grinders(settings("EK43"), &[] as &[&str]);
        let s = add_grinders_for_names(s, &["ek43", " Niche Zero ", "", "niche zero"]);
        assert_eq!(names(&s), ["EK43", "Niche Zero"]);
        assert_eq!(active_name(&s), Some("EK43"), "the active grinder stays");
        assert_eq!(add_grinders_for_names(s.clone(), &["Niche Zero"]), s);
    }

    #[test]
    fn an_import_before_the_migration_leaves_it_to_the_migration() {
        let s = settings("EK43");
        assert_eq!(add_grinders_for_names(s.clone(), &["Niche Zero"]), s);
    }

    #[test]
    fn adding_a_grinder_before_the_migration_migrates_the_settings_grinder_first() {
        let (s, _) = add_grinder(settings("EK43"), "Niche Zero", None);
        assert_eq!(names(&s), ["EK43", "Niche Zero"]);
        assert_eq!(active_name(&s), Some("EK43"));
    }

    #[test]
    fn an_import_names_json_skips_non_strings() {
        let out: CommonSettings = serde_json::from_str(&add_grinders_for_names_json(
            &serde_json::to_string(&migrate_grinders::<&str>(settings(""), &[])).unwrap(),
            r#"["EK43", 3, null, "Niche Zero"]"#,
        ))
        .unwrap();
        assert_eq!(names(&out), ["EK43", "Niche Zero"]);
        assert_eq!(active_name(&out), Some("EK43"));
    }

    // ── Adding and removing ──

    #[test]
    fn add_grinder_takes_the_named_preset_and_mints_a_unique_id() {
        let (s, id) = add_grinder(settings(""), "Bar grinder", Some("bookoo-motto-80"));
        let (s, id2) = add_grinder(s, "Bar grinder", None);
        assert_eq!(id.as_deref(), Some("grinder:bar-grinder"));
        assert_eq!(id2.as_deref(), Some("grinder:bar-grinder-2"));
        let gs = s.grinders.as_deref().unwrap();
        assert_eq!(gs[0].preset_id.as_deref(), Some("bookoo-motto-80"));
        assert_eq!(gs[1].scale, GrindScale::default());
        assert_eq!(s.active_grinder_id, id, "the first one added is active");
        assert_eq!(add_grinder(s.clone(), " ", None), (s, None));
    }

    #[test]
    fn removing_the_active_grinder_activates_the_first_left() {
        let s = migrate_grinders(settings("EK43"), &["Niche Zero"]);
        let s = remove_grinder(s, "grinder:ek43");
        assert_eq!(names(&s), ["Niche Zero"]);
        assert_eq!(active_name(&s), Some("Niche Zero"));
        assert_eq!(s.grinder_model, "EK43", "the old field stays");
    }

    // ── Settings storage ──

    #[test]
    fn owned_grinders_survive_the_settings_sanitize_and_restore_defaults() {
        let s = migrate_grinders(settings("Motto 80"), &["Niche Zero"]);
        let json = serde_json::to_value(&s).unwrap();
        assert_eq!(sanitize_common_settings(&json), s);
        let reset = reset_settings(&s, 0.0).common;
        assert_eq!(reset.grinders, s.grinders);
        assert_eq!(reset.active_grinder_id, s.active_grinder_id);
        assert_eq!(reset.grinder_model, "Motto 80");
    }

    #[test]
    fn a_blob_from_before_488_reads_as_not_migrated() {
        let c = sanitize_common_settings(&serde_json::json!({ "grinderModel": "EK43" }));
        assert_eq!(c.grinders, None);
        assert_eq!(c.grinder_model, "EK43");
        let out = serde_json::to_string(&c).unwrap();
        assert!(!out.contains("\"grinders\""), "{out}");
    }

    #[test]
    fn a_bad_grinder_entry_or_field_keeps_the_rest_of_the_list() {
        let c = sanitize_common_settings(&serde_json::json!({
            "grinders": [
                { "id": "grinder:a", "name": "A", "scale": { "min": "low", "max": 40, "step": 1, "unit": "clicks" } },
                "not a grinder",
                { "id": "grinder:b", "name": 7, "presetId": null },
            ],
            "activeGrinderId": "grinder:a",
        }));
        let gs = c.grinders.expect("the list is kept");
        assert_eq!(gs.len(), 2);
        assert_eq!(gs[0].name, "A");
        assert_eq!(
            gs[0].scale,
            GrindScale {
                min: 0.0,
                max: 40.0,
                step: 1.0,
                unit: "clicks".to_owned()
            }
        );
        assert_eq!((gs[1].id.as_str(), gs[1].name.as_str()), ("grinder:b", ""));
        assert_eq!(c.active_grinder_id.as_deref(), Some("grinder:a"));
    }

    // ── Last grind and the grind on select ──

    fn last(grinder: &str, grind: &str, at: i64) -> LastGrind {
        LastGrind {
            grinder: grinder.to_owned(),
            grind: grind.to_owned(),
            completed_at: at,
        }
    }

    fn select(active: Option<&str>, lasts: Vec<LastGrind>, bag: (&str, &str)) -> Option<String> {
        grind_on_select(&GrindOnSelectInput {
            active_grinder: active.map(str::to_owned),
            last_grinds: lasts,
            bag_grinder: bag.0.to_owned(),
            bag_grinder_setting: bag.1.to_owned(),
        })
    }

    #[test]
    fn select_takes_the_newest_shot_with_the_active_grinder() {
        let lasts = vec![
            last("EK43", "9", 30),
            last("niche zero", "14", 20),
            last("Niche Zero", "15", 10),
        ];
        assert_eq!(
            select(Some("Niche Zero"), lasts, ("Niche Zero", "12")).as_deref(),
            Some("14")
        );
    }

    #[test]
    fn select_falls_back_to_the_bags_setting_when_its_grinder_is_active() {
        let lasts = vec![last("EK43", "9", 30)];
        assert_eq!(
            select(Some(" niche zero"), lasts, ("Niche Zero", "6 + a tooth")).as_deref(),
            Some("6 + a tooth")
        );
    }

    #[test]
    fn select_leaves_the_grind_when_the_bags_grinder_is_another() {
        assert_eq!(select(Some("EK43"), Vec::new(), ("Niche Zero", "12")), None);
        assert_eq!(select(Some("EK43"), Vec::new(), ("EK43", "  ")), None);
    }

    #[test]
    fn select_with_no_active_grinder_leaves_the_grind() {
        assert_eq!(
            select(None, vec![last("EK43", "9", 1)], ("EK43", "9")),
            None
        );
        assert_eq!(
            select(Some(" "), vec![last("EK43", "9", 1)], ("EK43", "9")),
            None
        );
    }

    #[test]
    fn select_json_answers_a_string_or_null() {
        let input = serde_json::json!({
            "activeGrinder": "EK43",
            "lastGrinds": [{ "grinder": "EK43", "grind": "9.5", "completedAt": 1 }],
        });
        assert_eq!(grind_on_select_json(&input.to_string()), "\"9.5\"");
        assert_eq!(grind_on_select_json("{}"), "null");
        assert_eq!(grind_on_select_json("not json"), "null");
    }
}

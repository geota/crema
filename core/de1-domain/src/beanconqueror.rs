//! Beanconqueror export importer.
//!
//! Reads the JSON shape Beanconqueror (`~/code/crema-design/Beanconqueror`)
//! exports via `src/services/uiExportImportHelper.ts` and maps the
//! high-value subset onto Crema's [`Bean`] / [`Roaster`] / [`StoredShot`]
//! types. The shell unzips the export (one main `Beanconqueror.json`
//! plus chunk files for BREWS/BEANS over 500 items), merges chunks into
//! one main JSON, and hands the merged text here.
//!
//! ## What we import
//!
//! - `BEANS[]` → [`Bean`] + [`Roaster`] (dedup'd by case-insensitive
//!   roaster name).
//! - `BREWS[]` whose referenced `PREPARATION.style_type == "ESPRESSO"`
//!   → [`StoredShot`] with metadata + bean snapshot, **no telemetry**
//!   (v1 keeps the `samples` vec empty; the dense `flow_profile` files
//!   live alongside in the ZIP but their parse is deferred).
//!
//! ## What we skip
//!
//! - Non-espresso brews — counted in diagnostics, but Crema has no
//!   surface for V60 / AeroPress / etc.
//! - `WATER`, `GREEN_BEANS`, `ROASTING_MACHINES`, `SETTINGS`, `VERSION`,
//!   `GRAPH` — no Crema analogue today.
//! - Per-bean fields Crema doesn't model (cupping form, frozen-group
//!   tracking, EAN, image attachments, CO2e, etc.) — listed in
//!   diagnostics so the user sees what was dropped.
//!
//! ## Wire-form quirks
//!
//! Beanconqueror has migrated towards protobuf serialisation in
//! parallel with its legacy JSON storage. Enum fields like `roast` can
//! appear on the wire as any of:
//!
//! - the legacy TS enum *value* (`"Unknown"`, `"City+ Roast"`),
//! - the proto enum *name* (`"UNKNOWN_ROAST"`, `"CITY_PLUS_ROAST"`),
//! - a suffix-stripped key (`"UNKNOWN"`, `"CITY_PLUS"`), or
//! - the proto integer index (0..13).
//!
//! [`BcRoast`]'s deserializer accepts all four; an unrecognised value
//! falls through to `Unknown` and contributes a diagnostic.
//!
//! See `docs/46-beanconqueror-import-design.md` for the full mapping
//! table and follow-up plan (telemetry, export-back-to-BC, JSONL).

use serde::{Deserialize, Deserializer, Serialize};
use serde_json::Value;
use std::time::Duration;

use crate::{
    Bean, BeanMix, BeanOrigin, BeanRoastType, Roaster, ShotMetadata, ShotRecord, StoredShot,
};

// ── Wire types — partial; serde drops unknown fields ─────────────────

/// The merged Beanconqueror export. The shell unzips + concatenates
/// chunks before handing the JSON here. Each top-level field is an
/// array of records; we only model the keys we use.
#[derive(Debug, Default, Clone, Deserialize)]
pub struct BcExport {
    #[serde(rename = "BEANS", default)]
    pub beans: Vec<BcBean>,
    #[serde(rename = "BREWS", default)]
    pub brews: Vec<BcBrew>,
    #[serde(rename = "MILL", default)]
    pub mills: Vec<BcMill>,
    #[serde(rename = "PREPARATION", default)]
    pub preparations: Vec<BcPreparation>,
    #[serde(rename = "VERSION", default)]
    pub version: Vec<BcVersion>,
}

/// The export's `VERSION` record — lets the importer report which Beanconqueror
/// app version (hence schema era) the export came from.
/// `alreadyDisplayedVersions` is the chronological list of app versions the
/// user has run; its last entry is the export's app version. There is no
/// dedicated schema-version field in BC, so this is the best available signal —
/// the actual field-shape handling is presence-based, not version-gated.
#[derive(Debug, Default, Clone, Deserialize)]
pub struct BcVersion {
    #[serde(default, rename = "alreadyDisplayedVersions")]
    pub already_displayed_versions: Vec<String>,
}

/// The `config` wrapper that BC stamps on every entity — UUID + the
/// creation timestamp (Unix **seconds**, not milliseconds).
#[derive(Debug, Default, Clone, Deserialize)]
pub struct BcConfig {
    #[serde(default)]
    pub uuid: String,
    /// BC stores this as Unix seconds. Multiply by 1000 to get Crema's
    /// epoch-ms convention.
    #[serde(default, deserialize_with = "de_i64")]
    pub unix_timestamp: i64,
}

#[derive(Debug, Default, Clone, Deserialize)]
pub struct BcBean {
    #[serde(default)]
    pub config: BcConfig,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub roaster: String,
    #[serde(default)]
    pub roast: BcRoast,
    /// Crema's 1..10 roast scale lives in the BC `roast_range` field.
    /// When present and in range, we prefer it over the named `roast`.
    #[serde(default, deserialize_with = "de_i32")]
    pub roast_range: i32,
    #[serde(default)]
    pub note: String,
    /// BC's free-text flavour-descriptor field ("Aromatics / Flavour").
    /// Distinct from `note` — BC presents this as a separate input on
    /// the bean editor. Crema folds both into `tasting_notes` on import.
    #[serde(default)]
    pub aromatics: String,
    /// What the user paid for the bag. Currency-less; BC's currency
    /// lives in app-level settings (we drop those, so the bare number
    /// rides through). `0.0` (BC's "unset" sentinel) maps to `None` on
    /// the Crema side via the `cost > 0.0` guard in the mapper.
    #[serde(default, deserialize_with = "de_f32")]
    pub cost: f32,
    #[serde(default, rename = "beanMix")]
    pub bean_mix: BcBeanMix,
    /// Roasted-for category. Wire form may be any of the legacy TS
    /// value (`"Espresso"`), the proto name (`"ESPRESSO"` /
    /// `"UNKNOWN_BEAN_ROASTING_TYPE"`), or the proto integer index
    /// (0..3); see [`BcBeanRoastingType`].
    #[serde(default, rename = "bean_roasting_type")]
    pub bean_roasting_type: BcBeanRoastingType,
    #[serde(default)]
    pub decaffeinated: bool,
    #[serde(default)]
    pub favourite: bool,
    #[serde(default)]
    pub finished: bool,
    #[serde(default, deserialize_with = "de_f32")]
    pub weight: f32,
    #[serde(default, deserialize_with = "de_f32")]
    pub rating: f32,
    #[serde(default, rename = "roastingDate")]
    pub roasting_date: String,
    #[serde(default, rename = "buyDate")]
    pub buy_date: String,
    #[serde(default, rename = "openDate")]
    pub open_date: String,
    #[serde(default, rename = "frozenDate")]
    pub frozen_date: String,
    #[serde(default, rename = "unfrozenDate")]
    pub unfrozen_date: String,
    #[serde(default)]
    pub url: String,
    #[serde(default)]
    pub bean_information: Vec<BcBeanInformation>,
    /// File names of bag photos. BC stores photos OUT of the ZIP as
    /// sibling files (e.g. `photo_<uuid>.jpg`) and references them
    /// here. The "full with photos" export drops the ZIP + the photo
    /// files into a folder; the "slim" export omits photos entirely
    /// (`attachments: []`). Crema captures the names for the
    /// import-summary diagnostic; the byte storage hookup is a
    /// follow-up (`Bean.image_ref` exists in the model but the
    /// IndexedDB pipeline isn't wired yet).
    #[serde(default)]
    pub attachments: Vec<String>,
    /// Legacy (pre-v5.2.0) single-image path. BC's UPDATE_1 moved this into
    /// `attachments[]`; recovered as a photo when `attachments` is empty.
    #[serde(default, rename = "filePath")]
    pub file_path: String,
    /// Legacy (pre-v5.2.0) flat origin fields. BC's UPDATE_1 moved
    /// `country` / `variety` / `processing` off the bean and into
    /// `bean_information[]`; recovered as the bean's origin when that array is
    /// absent. Newer exports don't carry these top-level keys, so they default
    /// empty and the `bean_information[]` path wins.
    #[serde(default)]
    pub country: String,
    #[serde(default)]
    pub variety: String,
    #[serde(default)]
    pub processing: String,
    /// Catch-all for fields we don't model (cupping form, frozen-group
    /// tracking, EAN, …). Surface in diagnostics, drop on
    /// the Crema side.
    #[serde(flatten)]
    pub _other: serde_json::Map<String, Value>,
}

#[derive(Debug, Default, Clone, Deserialize, Serialize)]
pub struct BcBeanInformation {
    #[serde(default)]
    pub country: String,
    #[serde(default)]
    pub region: String,
    #[serde(default)]
    pub farm: String,
    #[serde(default)]
    pub farmer: String,
    #[serde(default)]
    pub variety: String,
    #[serde(default)]
    pub elevation: String,
    #[serde(default)]
    pub processing: String,
    #[serde(default)]
    pub harvest_time: String,
}

/// Deserialize a field Beanconqueror may store as either a JSON string or
/// a number. `grind_size` is the known offender: numeric on stepped
/// grinders (`5`), a string on others (`"5.5"`, `"fine"`). Numbers are
/// stringified; null / absent / non-scalar yield an empty string (which
/// the mapper's `opt_nonempty` then treats as "unset"). Without this,
/// serde hard-errors ("invalid type: integer `5`, expected a string") on
/// the first numeric `grind_size` and the entire import — every bean and
/// brew — is lost.
fn string_or_number<'de, D: Deserializer<'de>>(d: D) -> Result<String, D::Error> {
    Ok(match Value::deserialize(d)? {
        Value::String(s) => s,
        Value::Number(n) => n.to_string(),
        _ => String::new(),
    })
}

/// Coerce a Beanconqueror numeric field that may arrive as a JSON number OR a
/// numeric **string**. Pre-v5.2-era exports predate BC's `fixDataTypes()`
/// (which coerced cost / weight / grind_weight / brew_temperature from
/// string→number), and — BC being a JS app — any numeric field can show up
/// quoted. A non-numeric string, null, or other value coerces to 0, matching
/// the `#[serde(default)]` BC import already relies on. Without this, a single
/// stringified number aborts the entire parse (cf. the `grind_size` regression).
fn num_value(v: &Value) -> f64 {
    match v {
        Value::Number(n) => n.as_f64().unwrap_or(0.0),
        Value::String(s) => s.trim().parse::<f64>().unwrap_or(0.0),
        _ => 0.0,
    }
}

#[allow(clippy::cast_possible_truncation)]
fn de_f32<'de, D: Deserializer<'de>>(d: D) -> Result<f32, D::Error> {
    Ok(num_value(&Value::deserialize(d)?) as f32)
}

fn de_f64<'de, D: Deserializer<'de>>(d: D) -> Result<f64, D::Error> {
    Ok(num_value(&Value::deserialize(d)?))
}

#[allow(clippy::cast_possible_truncation)]
fn de_i32<'de, D: Deserializer<'de>>(d: D) -> Result<i32, D::Error> {
    Ok(num_value(&Value::deserialize(d)?) as i32)
}

#[allow(
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_precision_loss
)]
fn de_i64<'de, D: Deserializer<'de>>(d: D) -> Result<i64, D::Error> {
    Ok(num_value(&Value::deserialize(d)?) as i64)
}

#[derive(Debug, Default, Clone, Deserialize)]
pub struct BcBrew {
    #[serde(default)]
    pub config: BcConfig,
    /// UUID reference into `BEANS[]`.
    #[serde(default)]
    pub bean: String,
    /// UUID reference into `MILL[]`.
    #[serde(default)]
    pub mill: String,
    /// UUID reference into `PREPARATION[]`.
    #[serde(default)]
    pub method_of_preparation: String,
    #[serde(default, deserialize_with = "string_or_number")]
    pub grind_size: String,
    #[serde(default, deserialize_with = "de_f32")]
    pub grind_weight: f32,
    #[serde(default, deserialize_with = "de_f64")]
    pub brew_time: f64,
    #[serde(default, deserialize_with = "de_f64")]
    pub brew_time_milliseconds: f64,
    #[serde(default, deserialize_with = "de_f32")]
    pub brew_temperature: f32,
    /// Recipe input weight (grams).
    #[serde(default, deserialize_with = "de_f32")]
    pub bean_weight_in: f32,
    /// Beverage output (grams when `brew_beverage_quantity_type == "gr"`).
    #[serde(default, deserialize_with = "de_f32")]
    pub brew_beverage_quantity: f32,
    #[serde(default)]
    pub brew_beverage_quantity_type: BcQuantityKind,
    /// Legacy (pre-v5.2.0) beverage output. BC's UPDATE_1 renamed
    /// `brew_quantity` → `brew_beverage_quantity` for espresso preparations;
    /// older exports still carry the value here. The mapper falls back to this
    /// when `brew_beverage_quantity` is 0 (mirrors BC's migration).
    #[serde(default, deserialize_with = "de_f32")]
    pub brew_quantity: f32,
    #[serde(default)]
    pub brew_quantity_type: BcQuantityKind,
    #[serde(default, deserialize_with = "de_f32")]
    pub rating: f32,
    #[serde(default)]
    pub note: String,
    /// Path to a separate flow-profile JSON in the ZIP (`graphs/<uuid>_…json`).
    /// Telemetry import is deferred to a follow-up; we capture the string
    /// so a future commit can resolve it.
    #[serde(default)]
    pub flow_profile: String,
}

#[derive(Debug, Default, Clone, Deserialize)]
pub struct BcMill {
    #[serde(default)]
    pub config: BcConfig,
    #[serde(default)]
    pub name: String,
}

#[derive(Debug, Default, Clone, Deserialize)]
pub struct BcPreparation {
    #[serde(default)]
    pub config: BcConfig,
    #[serde(default)]
    pub name: String,
    /// The `style_type` field carries the espresso-vs-pour-over
    /// classification (`"ESPRESSO"` / `"POUR OVER"` / `"FULL_IMMERSION"` /
    /// `"PERCOLATION"`). Added in BC v5.2.0; older exports omit it, so the
    /// mapper derives espresso-ness from [`BcPreparation::prep_type`] instead
    /// (see [`is_espresso_type`]).
    #[serde(default, rename = "style_type")]
    pub style_type: String,
    /// The preparation `type` (a `PREPARATION_TYPES` value like `"PORTAFILTER"`
    /// or `"V60"`). Used to derive espresso-ness for pre-v5.2.0 exports that
    /// have no `style_type`, mirroring BC's `getPresetStyleType()`.
    #[serde(default, rename = "type")]
    pub prep_type: String,
}

// ── Defensive enums ──────────────────────────────────────────────────

/// Beanconqueror's roast enum. Mapped to Crema's 1..10 roast level via
/// [`BcRoast::to_crema_level`]. Accepts the legacy TS wire value, the
/// proto enum name, a suffix-stripped key, and the proto integer index.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Serialize)]
pub enum BcRoast {
    #[default]
    Unknown,
    Cinnamon,
    American,
    NewEngland,
    HalfCity,
    ModerateLight,
    City,
    CityPlus,
    FullCity,
    FullCityPlus,
    Italian,
    Vienna,
    French,
    Custom,
}

impl BcRoast {
    /// Map to Crema's 1..10 roast level. `Unknown` and `Custom` return
    /// `None` (the bean carries no roast level instead of guessing).
    #[must_use]
    pub fn to_crema_level(self) -> Option<u8> {
        match self {
            BcRoast::Cinnamon => Some(1),
            BcRoast::American | BcRoast::NewEngland => Some(2),
            BcRoast::HalfCity | BcRoast::ModerateLight => Some(3),
            BcRoast::City => Some(4),
            BcRoast::CityPlus => Some(5),
            BcRoast::FullCity => Some(6),
            BcRoast::FullCityPlus => Some(7),
            BcRoast::Vienna => Some(8),
            BcRoast::Italian => Some(9),
            BcRoast::French => Some(10),
            BcRoast::Unknown | BcRoast::Custom => None,
        }
    }
}

impl<'de> Deserialize<'de> for BcRoast {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        let v = Value::deserialize(d)?;
        // Integer form: proto enum index 0..13.
        if let Some(n) = v.as_i64() {
            return Ok(match n {
                1 => BcRoast::Cinnamon,
                2 => BcRoast::American,
                3 => BcRoast::NewEngland,
                4 => BcRoast::HalfCity,
                5 => BcRoast::ModerateLight,
                6 => BcRoast::City,
                7 => BcRoast::CityPlus,
                8 => BcRoast::FullCity,
                9 => BcRoast::FullCityPlus,
                10 => BcRoast::Italian,
                11 => BcRoast::Vienna,
                12 => BcRoast::French,
                13 => BcRoast::Custom,
                _ => BcRoast::Unknown,
            });
        }
        // String form: match TS enum value, proto enum name, or
        // suffix-stripped key. Case-insensitive on the underscores; the
        // legacy TS values include spaces and `+` which the
        // `.replace`d match handles.
        let Some(s) = v.as_str() else {
            return Ok(BcRoast::Unknown);
        };
        // Normalise to upper-snake, preserving `+` as `_PLUS_` so the
        // legacy TS values `"City+ Roast"` / `"Full City + Roast"` end
        // up as `CITY_PLUS_ROAST` / `FULL_CITY_PLUS_ROAST` instead of
        // collapsing into `CITY_ROAST`.
        let mut key = s.trim().replace('+', "_PLUS_").to_ascii_uppercase();
        for c in [' ', '-'] {
            key = key.replace(c, "_");
        }
        while key.contains("__") {
            key = key.replace("__", "_");
        }
        let key = key.trim_matches('_');
        Ok(match key {
            "" | "UNKNOWN" | "UNKNOWN_ROAST" => BcRoast::Unknown,
            "CINNAMON" | "CINNAMON_ROAST" => BcRoast::Cinnamon,
            "AMERICAN" | "AMERICAN_ROAST" => BcRoast::American,
            "NEW_ENGLAND" | "NEW_ENGLAND_ROAST" => BcRoast::NewEngland,
            "HALF_CITY" | "HALF_CITY_ROAST" => BcRoast::HalfCity,
            "MODERATE_LIGHT" | "MODERATE_LIGHT_ROAST" => BcRoast::ModerateLight,
            "CITY" | "CITY_ROAST" => BcRoast::City,
            "CITY_PLUS" | "CITY_PLUS_ROAST" => BcRoast::CityPlus,
            "FULL_CITY" | "FULL_CITY_ROAST" => BcRoast::FullCity,
            "FULL_CITY_PLUS" | "FULL_CITY_PLUS_ROAST" => BcRoast::FullCityPlus,
            "ITALIAN" | "ITALIAN_ROAST" => BcRoast::Italian,
            "VIENNA" | "VIEANNA" | "VIENNA_ROAST" | "VIEANNA_ROAST" => BcRoast::Vienna,
            "FRENCH" | "FRENCH_ROAST" => BcRoast::French,
            "CUSTOM" | "CUSTOM_ROAST" => BcRoast::Custom,
            _ => BcRoast::Unknown,
        })
    }
}

/// Beanconqueror's `beanMix` field. Round-trips to Crema's [`BeanMix`].
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Serialize)]
pub enum BcBeanMix {
    #[default]
    Unknown,
    SingleOrigin,
    Blend,
}

impl BcBeanMix {
    #[must_use]
    pub fn to_crema(self) -> Option<BeanMix> {
        match self {
            BcBeanMix::SingleOrigin => Some(BeanMix::Single),
            BcBeanMix::Blend => Some(BeanMix::Blend),
            BcBeanMix::Unknown => None,
        }
    }
}

impl<'de> Deserialize<'de> for BcBeanMix {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        let v = Value::deserialize(d)?;
        if let Some(n) = v.as_i64() {
            return Ok(match n {
                1 => BcBeanMix::SingleOrigin,
                2 => BcBeanMix::Blend,
                _ => BcBeanMix::Unknown,
            });
        }
        let Some(s) = v.as_str() else {
            return Ok(BcBeanMix::Unknown);
        };
        let key = s
            .trim()
            .to_ascii_uppercase()
            .replace([' ', '-'], "_")
            .replace("__", "_");
        Ok(match key.as_str() {
            "SINGLE_ORIGIN" | "SINGLE" => BcBeanMix::SingleOrigin,
            "BLEND" => BcBeanMix::Blend,
            _ => BcBeanMix::Unknown,
        })
    }
}

/// Beanconqueror's `bean_roasting_type` enum. Maps to Crema's
/// [`BeanRoastType`] via [`BcBeanRoastingType::to_crema`]; the
/// `Unknown` variant returns `None` so the bag carries no roast-type
/// rather than guessing.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Serialize)]
pub enum BcBeanRoastingType {
    #[default]
    Unknown,
    Filter,
    Espresso,
    Omni,
}

impl BcBeanRoastingType {
    #[must_use]
    pub fn to_crema(self) -> Option<BeanRoastType> {
        match self {
            BcBeanRoastingType::Espresso => Some(BeanRoastType::Espresso),
            BcBeanRoastingType::Filter => Some(BeanRoastType::Filter),
            BcBeanRoastingType::Omni => Some(BeanRoastType::Omni),
            BcBeanRoastingType::Unknown => None,
        }
    }
}

impl<'de> Deserialize<'de> for BcBeanRoastingType {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        let v = Value::deserialize(d)?;
        if let Some(n) = v.as_i64() {
            return Ok(match n {
                1 => BcBeanRoastingType::Filter,
                2 => BcBeanRoastingType::Espresso,
                3 => BcBeanRoastingType::Omni,
                _ => BcBeanRoastingType::Unknown,
            });
        }
        let Some(s) = v.as_str() else {
            return Ok(BcBeanRoastingType::Unknown);
        };
        let key = s.trim().to_ascii_uppercase();
        Ok(match key.as_str() {
            "FILTER" => BcBeanRoastingType::Filter,
            "ESPRESSO" => BcBeanRoastingType::Espresso,
            "OMNI" => BcBeanRoastingType::Omni,
            _ => BcBeanRoastingType::Unknown,
        })
    }
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Serialize)]
pub enum BcQuantityKind {
    #[default]
    Grams,
    Millilitres,
}

impl<'de> Deserialize<'de> for BcQuantityKind {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        let v = Value::deserialize(d)?;
        let Some(s) = v.as_str() else {
            return Ok(BcQuantityKind::Grams);
        };
        Ok(match s.trim().to_ascii_uppercase().as_str() {
            "ML" | "MILLILITRES" | "MILLILITERS" => BcQuantityKind::Millilitres,
            _ => BcQuantityKind::Grams,
        })
    }
}

// ── Import plan + diagnostics ────────────────────────────────────────

/// The mapped Crema-side records ready to apply, plus diagnostics.
/// JSON-serializable so the wasm / uniffi bridges can hand it across
/// the FFI boundary as one string.
#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportPlan {
    pub beans: Vec<Bean>,
    pub roasters: Vec<Roaster>,
    /// Each shot ships the core [`StoredShot`] (sans-IO; no ShotBean
    /// snapshot — that's a shell-side enrichment) alongside the
    /// resolved bean / grinder / roaster strings so the shell can build
    /// its own enriched record on apply.
    pub shots: Vec<ImportedShot>,
    pub diagnostics: ImportDiagnostics,
}

/// A shot ready to feed into the shell's `HistoryStore.upsert`, with
/// the cross-reference resolved into plain strings the shell uses to
/// build its enriched record (ShotBean snapshot, grinder model, etc.).
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportedShot {
    pub stored_shot: StoredShot,
    /// FK into the bean library we just imported. `None` when the BC
    /// brew's `bean` reference dangled.
    pub bean_id: Option<String>,
    /// Snapshot strings for the shell's enriched record.
    pub bean_name: String,
    pub roaster_name: Option<String>,
    pub roasted_on: Option<String>,
    pub roast_level: Option<u8>,
    pub grinder_model: Option<String>,
}

/// Counts + notes for the import-summary UI.
#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportDiagnostics {
    /// The Beanconqueror app version the export came from (the last entry of
    /// `VERSION[0].alreadyDisplayedVersions`), e.g. `"8.6.0"`. `None` when the
    /// export carries no VERSION record. Surfaced so the import summary can note
    /// the source schema era — the importer tolerates every shape back to v4.0.
    pub source_app_version: Option<String>,
    /// Number of brews skipped because their preparation wasn't espresso.
    pub non_espresso_brews_skipped: usize,
    /// Number of brews skipped because their preparation reference
    /// dangles (no matching `PREPARATION[]` entry).
    pub brews_dangling_preparation: usize,
    /// Number of brews skipped because their bean reference dangles.
    pub brews_dangling_bean: usize,
    /// Number of beans we imported.
    pub beans_imported: usize,
    /// Number of roasters created (de-duped against the bean list).
    pub roasters_created: usize,
    /// Number of espresso shots we imported.
    pub shots_imported: usize,
    /// Categories of dropped per-bean fields, with occurrence counts.
    /// E.g. `("cupping form", 18)` if 18 beans carried a non-empty
    /// `cupping` object.
    pub dropped_bean_categories: Vec<(String, usize)>,
    /// Total bag-photo filenames referenced across all beans. Photos
    /// ride OUT of the BC ZIP as sibling files; this count is what the
    /// import-summary UI uses to nudge the user "drop the folder, not
    /// just the zip" when they have photos.
    pub bag_photos_referenced: usize,
    /// Filenames of every photo referenced (in BEANS[*].attachments[*]
    /// order). The shell can match these against files the user
    /// dropped alongside the ZIP to wire up `Bean.image_ref` once the
    /// IndexedDB pipeline lands.
    pub bag_photo_filenames: Vec<String>,
}

// ── Mapping ──────────────────────────────────────────────────────────

/// Parse a merged Beanconqueror export JSON.
///
/// # Errors
///
/// Returns the JSON parse error string when the payload isn't a
/// well-formed object. Unknown top-level keys are silently ignored;
/// missing keys default to empty arrays.
pub fn parse_export(json: &str) -> Result<BcExport, String> {
    serde_json::from_str(json).map_err(|e| e.to_string())
}

// ── Export back to Beanconqueror ─────────────────────────────────────

/// Build a Beanconqueror main JSON from Crema's library + history.
///
/// Inverse of [`bc_to_crema`] within the limits documented in
/// `docs/47-beanconqueror-field-mapping.md` §4. Lossy on the Crema
/// side (BC has no Roaster entity — flattens to bean.roaster
/// string; no tags / visualizer ids / canonical-roaster pointer).
/// Round-trippable: BC → Crema → BC re-emits the `metadata.beanconqueror.unmapped`
/// stash on every bean, so cupping form / EAN / qr_code / blend
/// extras survive the trip.
///
/// The shell handles ZIP packaging + sibling photo files; this fn
/// just produces the main `Beanconqueror.json` payload.
/// `shot_bean_ids` is a parallel slice to `shots`: each entry is the
/// Crema-side bean id (e.g. `"bean:019e65a5-…"`) the shot was pulled
/// against, or `None` for a beanless shot. We strip the `bean:` prefix
/// at write-time so the emitted BC brew's `bean` field matches the
/// emitted BC bean record's `config.uuid` (which is also the
/// prefix-stripped form). The shell-side adapter
/// [`crema_to_bc_main_json_from_envelope`] derives this vec from each
/// shot's `bean.beanId` snapshot before handing the rest to the core.
#[must_use]
pub fn crema_to_bc_main_json(
    beans: &[Bean],
    roasters: &[Roaster],
    shots: &[StoredShot],
    shot_bean_ids: &[Option<String>],
    now_unix_ms: i64,
) -> String {
    let roaster_by_id: std::collections::HashMap<&str, &Roaster> =
        roasters.iter().map(|r| (r.id.as_str(), r)).collect();

    // Synthesise one PREPARATION row referenced by every brew —
    // ESPRESSO style. BC tolerates a brews-only export but the brew's
    // `method_of_preparation` would dangle without it.
    let prep_uuid = "crema-espresso-preparation";
    let preparation = serde_json::json!({
        "config": { "uuid": prep_uuid, "unix_timestamp": now_unix_ms / 1000 },
        "name": "Espresso",
        "type": "PORTAFILTER",
        "style_type": "ESPRESSO",
        "finished": false,
        "attachments": []
    });

    // Distinct grinder models across shots → one MILL row each.
    let mut mill_uuids: std::collections::HashMap<String, String> =
        std::collections::HashMap::new();
    let mut mills_json: Vec<serde_json::Value> = Vec::new();
    // The Rust core's `ShotMetadata` doesn't carry an equipment-level
    // `grinderModel` — that's a shell field. BC's `MILL` rows need a
    // distinct equipment name per record; we leave MILL empty in the
    // sans-IO path and the shell adapter fills it in by walking its
    // own `StoredShot.grinderModel` snapshot.
    let _ = &mut mill_uuids;
    let _ = &mut mills_json;
    let _ = shots;

    let mut beans_json: Vec<serde_json::Value> = Vec::with_capacity(beans.len());
    for bean in beans {
        let roaster_name = bean
            .roaster_id
            .as_deref()
            .and_then(|id| roaster_by_id.get(id))
            .map(|r| r.name.clone())
            .unwrap_or_default();

        // Pull the unmapped + extra-blend-components stash back out
        // of metadata.beanconqueror so they round-trip verbatim.
        let bc_stash = bean
            .metadata
            .as_object()
            .and_then(|m| m.get("beanconqueror"))
            .and_then(|v| v.as_object());
        let photo_filenames = bc_stash
            .and_then(|s| s.get("photo_filenames"))
            .and_then(|v| v.as_array())
            .map(|a| {
                a.iter()
                    .filter_map(|v| v.as_str().map(str::to_owned))
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default();
        let bean_information_extra = bc_stash
            .and_then(|s| s.get("bean_information_extra"))
            .and_then(|v| v.as_array())
            .cloned()
            .unwrap_or_default();
        let unmapped = bc_stash
            .and_then(|s| s.get("unmapped"))
            .and_then(|v| v.as_object())
            .cloned()
            .unwrap_or_default();

        // Reverse the tasting-notes join: BC has separate note +
        // aromatics fields. We can't tell which half of the joined
        // string came from which BC field on the round-trip back,
        // so the safest choice is: put the whole thing in `note`
        // and leave `aromatics` empty. If the original BC import
        // stashed the split somewhere, future work could recover
        // it; for now a lossy reverse is acceptable per the doc.
        let note = bean.tasting_notes.clone();
        let aromatics = String::new();

        let mut bean_info: Vec<serde_json::Value> =
            Vec::with_capacity(1 + bean_information_extra.len());
        // Primary origin → bean_information[0].
        let mut primary = serde_json::Map::new();
        if let Some(c) = &bean.origin.country {
            primary.insert("country".to_owned(), serde_json::Value::String(c.clone()));
        }
        if let Some(r) = &bean.origin.region {
            primary.insert("region".to_owned(), serde_json::Value::String(r.clone()));
        }
        if let Some(f) = &bean.origin.farm {
            primary.insert("farm".to_owned(), serde_json::Value::String(f.clone()));
        }
        if let Some(f) = &bean.origin.farmer {
            primary.insert("farmer".to_owned(), serde_json::Value::String(f.clone()));
        }
        if let Some(v) = &bean.origin.variety {
            primary.insert("variety".to_owned(), serde_json::Value::String(v.clone()));
        }
        if let Some(e) = &bean.origin.elevation {
            primary.insert("elevation".to_owned(), serde_json::Value::String(e.clone()));
        }
        if let Some(p) = &bean.origin.processing {
            primary.insert(
                "processing".to_owned(),
                serde_json::Value::String(p.clone()),
            );
        }
        if let Some(h) = &bean.origin.harvest_time {
            primary.insert(
                "harvest_time".to_owned(),
                serde_json::Value::String(h.clone()),
            );
        }
        bean_info.push(serde_json::Value::Object(primary));
        for extra in bean_information_extra {
            bean_info.push(extra);
        }

        let mut obj = serde_json::Map::new();
        // Strip the `bean:` prefix so BC sees its native UUID shape.
        let uuid = bean.id.strip_prefix("bean:").unwrap_or(&bean.id).to_owned();
        obj.insert(
            "config".to_owned(),
            serde_json::json!({ "uuid": uuid, "unix_timestamp": bean.created_at / 1000 }),
        );
        obj.insert(
            "name".to_owned(),
            serde_json::Value::String(bean.name.clone()),
        );
        obj.insert(
            "roaster".to_owned(),
            serde_json::Value::String(roaster_name),
        );
        obj.insert(
            "roast".to_owned(),
            serde_json::Value::String(crema_roast_level_to_bc(bean.roast_level)),
        );
        obj.insert(
            "roast_range".to_owned(),
            serde_json::Value::from(bean.roast_level.unwrap_or(0)),
        );
        obj.insert("note".to_owned(), serde_json::Value::String(note));
        obj.insert("aromatics".to_owned(), serde_json::Value::String(aromatics));
        obj.insert(
            "cost".to_owned(),
            serde_json::Value::from(bean.cost.unwrap_or(0.0)),
        );
        obj.insert(
            "beanMix".to_owned(),
            serde_json::Value::String(match bean.mix {
                Some(BeanMix::Single) => "SINGLE_ORIGIN".to_owned(),
                Some(BeanMix::Blend) => "BLEND".to_owned(),
                None => "UNKNOWN".to_owned(),
            }),
        );
        obj.insert(
            "bean_roasting_type".to_owned(),
            serde_json::Value::String(match bean.roast_type {
                Some(BeanRoastType::Espresso) => "ESPRESSO".to_owned(),
                Some(BeanRoastType::Filter) => "FILTER".to_owned(),
                Some(BeanRoastType::Omni) => "OMNI".to_owned(),
                None => "UNKNOWN".to_owned(),
            }),
        );
        obj.insert(
            "decaffeinated".to_owned(),
            serde_json::Value::Bool(bean.decaf),
        );
        obj.insert(
            "favourite".to_owned(),
            serde_json::Value::Bool(bean.favourite),
        );
        obj.insert(
            "finished".to_owned(),
            serde_json::Value::Bool(bean.archived_at.is_some()),
        );
        obj.insert("weight".to_owned(), serde_json::Value::from(bean.bag_size));
        obj.insert("rating".to_owned(), serde_json::Value::from(bean.rating));
        obj.insert(
            "roastingDate".to_owned(),
            serde_json::Value::String(bean.roasted_on.clone().unwrap_or_default()),
        );
        obj.insert(
            "openDate".to_owned(),
            serde_json::Value::String(bean.opened_on.clone().unwrap_or_default()),
        );
        obj.insert(
            "frozenDate".to_owned(),
            serde_json::Value::String(bean.frozen_on.clone().unwrap_or_default()),
        );
        obj.insert(
            "unfrozenDate".to_owned(),
            serde_json::Value::String(bean.defrosted_on.clone().unwrap_or_default()),
        );
        obj.insert(
            "url".to_owned(),
            serde_json::Value::String(bean.url.clone().unwrap_or_default()),
        );
        obj.insert(
            "bean_information".to_owned(),
            serde_json::Value::Array(bean_info),
        );
        obj.insert(
            "attachments".to_owned(),
            serde_json::Value::Array(
                photo_filenames
                    .into_iter()
                    .map(serde_json::Value::String)
                    .collect(),
            ),
        );
        // Restore unmapped fields verbatim (cupping form, qr_code,
        // EAN, frozen-portion accounting, etc.).
        for (k, v) in unmapped {
            obj.entry(k).or_insert(v);
        }
        beans_json.push(serde_json::Value::Object(obj));
    }

    let mut brews_json: Vec<serde_json::Value> = Vec::with_capacity(shots.len());
    for (i, shot) in shots.iter().enumerate() {
        // Cap at u32::MAX ms (~49 days) — well within f64 mantissa
        // precision; longer durations are theoretical.
        #[allow(clippy::cast_precision_loss)]
        let dur_ms = (shot.record.duration.as_millis().min(u128::from(u32::MAX))) as f64;
        let dur_s = dur_ms / 1000.0;
        let mut obj = serde_json::Map::new();
        // The Rust `StoredShot` doesn't carry an id (that's a
        // shell-side concept). Synthesise one from the recorded
        // timestamp so BC sees a unique uuid-shaped string per brew.
        // The shell's id round-trip can be done by the shell adapter
        // if exact-id preservation is needed.
        let synth_uuid = format!("crema-shot-{}", shot.completed_at);
        obj.insert(
            "config".to_owned(),
            serde_json::json!({
                "uuid": synth_uuid,
                "unix_timestamp": (shot.completed_at / 1000) as i64
            }),
        );
        // Strip the `bean:` prefix so the brew's FK matches the
        // emitted bean record's `config.uuid` (same transformation
        // applied at the bean-record emission above).
        let bc_bean_uuid = shot_bean_ids
            .get(i)
            .and_then(Option::as_deref)
            .map(|id| id.strip_prefix("bean:").unwrap_or(id).to_owned())
            .unwrap_or_default();
        obj.insert("bean".to_owned(), serde_json::Value::String(bc_bean_uuid));
        obj.insert("mill".to_owned(), serde_json::Value::String(String::new()));
        obj.insert(
            "method_of_preparation".to_owned(),
            serde_json::Value::String(prep_uuid.to_owned()),
        );
        obj.insert(
            "grind_size".to_owned(),
            serde_json::Value::String(shot.metadata.grinder_setting.clone().unwrap_or_default()),
        );
        obj.insert(
            "bean_weight_in".to_owned(),
            serde_json::Value::from(shot.metadata.dose.unwrap_or(0.0)),
        );
        obj.insert("brew_time".to_owned(), serde_json::Value::from(dur_s));
        obj.insert(
            "brew_time_milliseconds".to_owned(),
            serde_json::Value::from(dur_ms),
        );
        obj.insert(
            "brew_beverage_quantity".to_owned(),
            serde_json::Value::from(shot.metadata.yield_out.unwrap_or(0.0)),
        );
        obj.insert(
            "brew_beverage_quantity_type".to_owned(),
            serde_json::Value::String("gr".to_owned()),
        );
        obj.insert(
            "rating".to_owned(),
            serde_json::Value::from(shot.metadata.rating.unwrap_or(0)),
        );
        obj.insert(
            "note".to_owned(),
            serde_json::Value::String(shot.metadata.notes.clone().unwrap_or_default()),
        );
        brews_json.push(serde_json::Value::Object(obj));
    }

    let main = serde_json::json!({
        "BEANS": beans_json,
        "BREWS": brews_json,
        "MILL": [],
        "PREPARATION": [preparation],
        "VERSION": [{
            "config": { "uuid": "crema-export", "unix_timestamp": now_unix_ms / 1000 },
            "alreadyDisplayedVersions": [],
            "updatedDataVersions": []
        }]
    });
    serde_json::to_string(&main).unwrap_or_else(|_| "{}".to_owned())
}

/// Crema's 1..10 roast level → the BC wire enum string. Buckets:
/// 1 → Cinnamon, 2 → American, … 10 → French. `None` → `"UNKNOWN"`.
fn crema_roast_level_to_bc(level: Option<u8>) -> String {
    match level {
        Some(1) => "CINNAMON_ROAST",
        Some(2) => "AMERICAN_ROAST",
        Some(3) => "HALF_CITY_ROAST",
        Some(4) => "CITY_ROAST",
        Some(5) => "CITY_PLUS_ROAST",
        Some(6) => "FULL_CITY_ROAST",
        Some(7) => "FULL_CITY_PLUS_ROAST",
        Some(8) => "VIEANNA_ROAST",
        Some(9) => "ITALIAN_ROAST",
        Some(10) => "FRENCH_ROAST",
        _ => "UNKNOWN",
    }
    .to_owned()
}

/// JSON-in / JSON-out adapter for the wasm + uniffi bridges. Envelope:
/// `{ "beans": [...], "roasters": [...], "shots": [...] }`. Returns
/// the Beanconqueror main JSON.
///
/// # Errors
///
/// Returns the JSON parse error string when the envelope is malformed.
pub fn crema_to_bc_main_json_from_envelope(
    envelope_json: &str,
    now_unix_ms: i64,
) -> Result<String, String> {
    #[derive(serde::Deserialize)]
    struct In {
        #[serde(default)]
        beans: Vec<Bean>,
        #[serde(default)]
        roasters: Vec<Roaster>,
        // Shots arrive as raw JSON values so the shell-only `bean`
        // sub-object can be extracted before the Rust `StoredShot`
        // parse (which drops unknown keys). The two-pass deserialize
        // — once as Value to pull `bean.beanId`, then per-entry into
        // `StoredShot` — keeps the cross-shell envelope shape free of
        // a Rust-level `bean` field while preserving the shot→bean
        // link the brew's `bean` UUID needs.
        #[serde(default)]
        shots: Vec<serde_json::Value>,
    }
    let inp: In = serde_json::from_str(envelope_json).map_err(|e| e.to_string())?;
    let mut shots: Vec<StoredShot> = Vec::with_capacity(inp.shots.len());
    let mut shot_bean_ids: Vec<Option<String>> = Vec::with_capacity(inp.shots.len());
    for value in inp.shots {
        let bean_id = value
            .get("bean")
            .and_then(serde_json::Value::as_object)
            .and_then(|obj| obj.get("beanId"))
            .and_then(serde_json::Value::as_str)
            .map(str::to_owned);
        let shot: StoredShot = serde_json::from_value(value).map_err(|e| e.to_string())?;
        shots.push(shot);
        shot_bean_ids.push(bean_id);
    }
    Ok(crema_to_bc_main_json(
        &inp.beans,
        &inp.roasters,
        &shots,
        &shot_bean_ids,
        now_unix_ms,
    ))
}

/// JSON-in / JSON-out adapter for the wasm + uniffi bridges. Takes the
/// merged Beanconqueror main export JSON and a wall-clock `now_unix_ms`
/// for the `created_at` / `updated_at` stamps; returns the
/// [`ImportPlan`] serialized as JSON (camelCase field names) for the
/// shell to apply.
///
/// Uses [`crate::new_profile_id`] for fresh roaster ids (UUID v7).
///
/// # Errors
///
/// Returns the JSON parse error string when the input isn't a
/// well-formed object.
pub fn import_beanconqueror_json(json: &str, now_unix_ms: i64) -> Result<String, String> {
    let export = parse_export(json)?;
    let plan = bc_to_crema(&export, now_unix_ms, crate::new_profile_id);
    serde_json::to_string(&plan).map_err(|e| e.to_string())
}

/// Map a parsed [`BcExport`] into Crema's domain types.
///
/// `now_unix_ms` stamps `created_at` / `updated_at` on the resulting
/// records (BC's `unix_timestamp` is preserved separately as the
/// shot's `recorded_at` / the bean's roasted-on date when available).
///
/// `mint_id` produces a fresh string id whenever we need one (a new
/// roaster row). The shell passes the same UUID v7 minter it uses
/// elsewhere (`new_profile_id` style); tests pass a sequential stub.
pub fn bc_to_crema<F>(export: &BcExport, now_unix_ms: i64, mut mint_id: F) -> ImportPlan
where
    F: FnMut() -> String,
{
    let mut plan = ImportPlan::default();

    // Schema-awareness: report the source app version (best-effort). The
    // field-shape handling below is presence-based, not gated on this — BC has
    // no dedicated schema-version field.
    plan.diagnostics.source_app_version = export
        .version
        .first()
        .and_then(|v| v.already_displayed_versions.last())
        .map(|s| s.trim().to_owned())
        .filter(|s| !s.is_empty());

    // ── Beans + Roasters ──
    // Dedup roasters case-insensitively. Each unique non-empty
    // `bean.roaster` string becomes one Roaster row; subsequent beans
    // referencing the same string share that row's id.
    use std::collections::HashMap;
    let mut roaster_by_lc: HashMap<String, String> = HashMap::new(); // lc_name -> roaster_id
    let mut drop_counts: HashMap<&'static str, usize> = HashMap::new();

    for bc_bean in &export.beans {
        let roaster_id = lookup_or_create_roaster(
            &bc_bean.roaster,
            &mut roaster_by_lc,
            &mut plan.roasters,
            now_unix_ms,
            &mut mint_id,
        );

        let bean_id = format!("bean:{}", bc_bean.config.uuid.trim());
        let mut bean = Bean::new(bean_id, bc_bean.name.trim().to_owned(), now_unix_ms);
        bean.roaster_id = roaster_id;
        bean.roasted_on = iso_date_opt(&bc_bean.roasting_date);
        bean.opened_on = iso_date_opt(&bc_bean.open_date);
        bean.frozen_on = iso_date_opt(&bc_bean.frozen_date);
        bean.defrosted_on = iso_date_opt(&bc_bean.unfrozen_date);
        // `roast_range` (1..10) wins over the named roast when in range.
        bean.roast_level = if (1..=10).contains(&bc_bean.roast_range) {
            u8::try_from(bc_bean.roast_range).ok()
        } else {
            bc_bean.roast.to_crema_level()
        };
        bean.mix = bc_bean.bean_mix.to_crema();
        bean.roast_type = bc_bean.bean_roasting_type.to_crema();
        bean.decaf = bc_bean.decaffeinated;
        bean.favourite = bc_bean.favourite;
        // Crema has two free-text fields on Bean: `notes` (general
        // free-form notes) and `tasting_notes` (the tasting-focused
        // box the editor surfaces under "Tasting"). BC's `note` and
        // `aromatics` map to the latter — both describe how the bag
        // tastes — joined with a blank line so they round-trip
        // readable. `notes` stays empty on import; the user can split
        // material out manually if they want to.
        let tn_parts: Vec<&str> = [bc_bean.note.trim(), bc_bean.aromatics.trim()]
            .into_iter()
            .filter(|s| !s.is_empty())
            .collect();
        bean.tasting_notes = tn_parts.join("\n\n");
        if bc_bean.cost > 0.0 && bc_bean.cost.is_finite() {
            bean.cost = Some(bc_bean.cost);
        }
        if bc_bean.rating >= 0.0 && bc_bean.rating <= 5.0 {
            #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
            let r = bc_bean.rating.round() as u8;
            bean.rating = r.min(5);
        }
        if bc_bean.weight > 0.0 {
            bean.bag_size = bc_bean.weight;
            // A `finished` bag has been consumed, so remaining = 0;
            // otherwise assume the bag is full (Crema's only signal
            // for "remaining" is auto-debit per shot).
            bean.remaining = if bc_bean.finished {
                0.0
            } else {
                bc_bean.weight
            };
        }
        if bc_bean.finished {
            bean.archived_at = Some(now_unix_ms);
        }
        if !bc_bean.url.is_empty() {
            bean.url = Some(bc_bean.url.clone());
        }
        // Origin — first entry of `bean_information[]`. BC supports
        // multiple components per blend; Crema models a single origin
        // string today, so we use [0].
        if let Some(first) = bc_bean.bean_information.first() {
            bean.origin = BeanOrigin {
                country: opt_nonempty(&first.country),
                region: opt_nonempty(&first.region),
                farm: opt_nonempty(&first.farm),
                farmer: opt_nonempty(&first.farmer),
                variety: opt_nonempty(&first.variety),
                elevation: opt_nonempty(&first.elevation),
                processing: opt_nonempty(&first.processing),
                harvest_time: opt_nonempty(&first.harvest_time),
            };
        } else if !bc_bean.country.trim().is_empty()
            || !bc_bean.variety.trim().is_empty()
            || !bc_bean.processing.trim().is_empty()
        {
            // Pre-v5.2.0 exports kept origin in flat `country` / `variety` /
            // `processing` fields on the bean (UPDATE_1 moved them into
            // `bean_information[]`). Recover them when the array is absent.
            bean.origin = BeanOrigin {
                country: opt_nonempty(&bc_bean.country),
                region: None,
                farm: None,
                farmer: None,
                variety: opt_nonempty(&bc_bean.variety),
                elevation: None,
                processing: opt_nonempty(&bc_bean.processing),
                harvest_time: None,
            };
        }
        // Provenance — round-trip the BC UUID so a re-import skips us.
        bean.beanconqueror_id = Some(bc_bean.config.uuid.clone());

        // Track dropped categories for the import-summary banner.
        for key in ["cupping", "cupped_flavor", "bean_roast_information"] {
            if has_non_empty(&bc_bean._other, key) {
                *drop_counts.entry(key).or_insert(0) += 1;
            }
        }
        // Stash everything Crema doesn't model first-class into
        // `metadata.beanconqueror.*` so a future export-back-to-BC can
        // put it on the wire verbatim. Three buckets:
        //
        //   - `photo_filenames`: derived from `attachments[]`, also
        //     driving the shell's filename → bean pairing for the
        //     dropped photo files (the catch-all `unmapped` block
        //     below doesn't carry `attachments` because we model the
        //     field explicitly).
        //   - `bean_information_extra`: blend components beyond [0].
        //     We consume [0] as the bean's primary origin; extras
        //     would otherwise be lost on import.
        //   - `unmapped`: every field BC ships that Crema doesn't
        //     model first-class — cupping form, cupped_flavor wheel,
        //     bean_roast_information (self-roast curve), qr_code,
        //     internal_share_code, shared, bestDate, buyDate,
        //     cupping_points, co2e_kg, frozen-group accounting
        //     (frozenId / frozenGroupId / frozenStorageType /
        //     frozenNote), ean_article_number, roast_custom, plus
        //     anything BC adds in future versions. Survives a round-
        //     trip without us having to enumerate the schema.
        let mut photos: Vec<String> = bc_bean
            .attachments
            .iter()
            .map(|s| s.trim().to_owned())
            .filter(|s| !s.is_empty())
            .collect();
        // Pre-v5.2.0 exports kept a single image in `filePath` (UPDATE_1 moved
        // it into `attachments[]`). Recover it when `attachments` is empty.
        if photos.is_empty() && !bc_bean.file_path.trim().is_empty() {
            photos.push(bc_bean.file_path.trim().to_owned());
        }
        let mut bc_meta = serde_json::Map::new();
        if !photos.is_empty() {
            for p in &photos {
                plan.diagnostics.bag_photo_filenames.push(p.clone());
            }
            bc_meta.insert(
                "photo_filenames".to_owned(),
                serde_json::Value::Array(
                    photos
                        .iter()
                        .map(|s| serde_json::Value::String(s.clone()))
                        .collect(),
                ),
            );
        }
        if bc_bean.bean_information.len() > 1 {
            let extras = serde_json::to_value(&bc_bean.bean_information[1..])
                .unwrap_or(serde_json::Value::Null);
            if !matches!(extras, serde_json::Value::Null) {
                bc_meta.insert("bean_information_extra".to_owned(), extras);
            }
        }
        if !bc_bean._other.is_empty() {
            bc_meta.insert(
                "unmapped".to_owned(),
                serde_json::Value::Object(bc_bean._other.clone()),
            );
        }
        if !bc_meta.is_empty() {
            let mut wrapper = serde_json::Map::new();
            wrapper.insert(
                "beanconqueror".to_owned(),
                serde_json::Value::Object(bc_meta),
            );
            bean.metadata = serde_json::Value::Object(wrapper);
        }

        plan.beans.push(bean);
    }
    plan.diagnostics.bag_photos_referenced = plan.diagnostics.bag_photo_filenames.len();

    plan.diagnostics.beans_imported = plan.beans.len();
    plan.diagnostics.roasters_created = plan.roasters.len();
    plan.diagnostics.dropped_bean_categories = drop_counts
        .into_iter()
        .map(|(k, v)| (k.to_owned(), v))
        .collect();
    plan.diagnostics
        .dropped_bean_categories
        .sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));

    // ── Brews → StoredShot (espresso-only, no telemetry yet) ──
    let bean_index: HashMap<&str, &BcBean> = export
        .beans
        .iter()
        .map(|b| (b.config.uuid.as_str(), b))
        .collect();
    let prep_is_espresso: HashMap<&str, bool> = export
        .preparations
        .iter()
        .map(|p| {
            // v5.2.0+ exports carry `style_type`; pre-v5.2.0 ones don't, so
            // derive espresso-ness from the preparation `type` instead —
            // otherwise every brew from an old export is skipped as "non-espresso".
            let espresso = if p.style_type.trim().is_empty() {
                is_espresso_type(&p.prep_type)
            } else {
                is_espresso_style(&p.style_type)
            };
            (p.config.uuid.as_str(), espresso)
        })
        .collect();
    let mill_by_uuid: HashMap<&str, &BcMill> = export
        .mills
        .iter()
        .map(|m| (m.config.uuid.as_str(), m))
        .collect();
    let crema_bean_by_bc_uuid: HashMap<&str, &Bean> = export
        .beans
        .iter()
        .zip(plan.beans.iter())
        .map(|(bc, cr)| (bc.config.uuid.as_str(), cr))
        .collect();
    let roaster_name_by_id: HashMap<&str, &str> = plan
        .roasters
        .iter()
        .map(|r| (r.id.as_str(), r.name.as_str()))
        .collect();

    for bc_brew in &export.brews {
        let prep_uuid = bc_brew.method_of_preparation.as_str();
        let espresso = match prep_is_espresso.get(prep_uuid) {
            Some(&v) => v,
            None => {
                if !prep_uuid.is_empty() {
                    plan.diagnostics.brews_dangling_preparation += 1;
                }
                continue;
            }
        };
        if !espresso {
            plan.diagnostics.non_espresso_brews_skipped += 1;
            continue;
        }
        let bean_uuid = bc_brew.bean.as_str();
        let bc_bean = match bean_index.get(bean_uuid) {
            Some(b) => *b,
            None => {
                if !bean_uuid.is_empty() {
                    plan.diagnostics.brews_dangling_bean += 1;
                }
                continue;
            }
        };

        // Build the StoredShot. Telemetry stays empty for v1; the
        // duration is taken from BC's brew_time + brew_time_milliseconds.
        let duration_ms = brew_duration_ms(bc_brew);
        let record = ShotRecord {
            duration: Duration::from_millis(duration_ms),
            samples: Vec::new(),
        };

        // BC stores Unix seconds on Config; Crema's StoredShot wants ms.
        let recorded_at_ms = (bc_brew.config.unix_timestamp as u64).saturating_mul(1000);
        let mut shot = StoredShot::new(recorded_at_ms, record);

        // Core metadata: dose / yield / rating / notes / grinder_setting.
        let crema_bean = crema_bean_by_bc_uuid.get(bean_uuid).copied();
        let grinder_model = mill_by_uuid
            .get(bc_brew.mill.as_str())
            .map(|m| m.name.trim().to_owned())
            .filter(|s| !s.is_empty());
        let dose = if bc_brew.bean_weight_in > 0.0 {
            bc_brew.bean_weight_in
        } else {
            bc_brew.grind_weight
        };
        // Espresso yield. v5.2.0+ exports use `brew_beverage_quantity` (+ type);
        // older ones store it in `brew_quantity` (+ type). Mirror BC's UPDATE_1
        // fallback: when `brew_beverage_quantity` is 0, use `brew_quantity`.
        let (qty, qty_type) = if bc_brew.brew_beverage_quantity > 0.0 {
            (
                bc_brew.brew_beverage_quantity,
                bc_brew.brew_beverage_quantity_type,
            )
        } else {
            (bc_brew.brew_quantity, bc_brew.brew_quantity_type)
        };
        let yield_out = (matches!(qty_type, BcQuantityKind::Grams) && qty > 0.0).then_some(qty);
        #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
        let rating_u = if bc_brew.rating >= 0.5 && bc_brew.rating <= 5.0 {
            Some(bc_brew.rating.round().min(5.0) as u8)
        } else {
            None
        };
        let beans_label = if !bc_bean.name.trim().is_empty() {
            let bn = bc_bean.name.trim();
            if !bc_bean.roaster.trim().is_empty() {
                Some(format!("{} · {}", bn, bc_bean.roaster.trim()))
            } else {
                Some(bn.to_owned())
            }
        } else {
            None
        };
        let meta = ShotMetadata {
            dose: (dose > 0.0).then_some(dose),
            yield_out,
            beans: beans_label,
            grinder_setting: opt_nonempty(&bc_brew.grind_size),
            notes: opt_nonempty(&bc_brew.note),
            rating: rating_u,
            tds: None,
            extraction_yield: None,
        };
        shot = shot.with_metadata(meta);

        let imported = ImportedShot {
            stored_shot: shot,
            bean_id: crema_bean.map(|b| b.id.clone()),
            bean_name: bc_bean.name.trim().to_owned(),
            roaster_name: crema_bean
                .and_then(|b| b.roaster_id.as_deref())
                .and_then(|id| roaster_name_by_id.get(id).copied())
                .map(str::to_owned),
            roasted_on: iso_date_opt(&bc_bean.roasting_date),
            roast_level: crema_bean.and_then(|b| b.roast_level),
            grinder_model,
        };
        plan.shots.push(imported);
    }

    plan.diagnostics.shots_imported = plan.shots.len();
    plan
}

// ── Helpers ──────────────────────────────────────────────────────────

fn lookup_or_create_roaster<F>(
    raw_name: &str,
    by_lc: &mut std::collections::HashMap<String, String>,
    roasters: &mut Vec<Roaster>,
    now_unix_ms: i64,
    mint_id: &mut F,
) -> Option<String>
where
    F: FnMut() -> String,
{
    let trimmed = raw_name.trim();
    if trimmed.is_empty() {
        return None;
    }
    let lc = trimmed.to_lowercase();
    if let Some(id) = by_lc.get(&lc) {
        return Some(id.clone());
    }
    let id = format!("roaster:{}", mint_id());
    by_lc.insert(lc, id.clone());
    roasters.push(Roaster::new(id.clone(), trimmed.to_owned(), now_unix_ms));
    Some(id)
}

fn iso_date_opt(s: &str) -> Option<String> {
    let t = s.trim();
    if t.is_empty() {
        return None;
    }
    // BC may carry an ISO-8601 timestamp or just a date. Slice the
    // first 10 chars (`yyyy-mm-dd`) if it looks ISO-shaped; else
    // return as-is and let downstream validate.
    if t.len() >= 10 && t.chars().nth(4) == Some('-') && t.chars().nth(7) == Some('-') {
        Some(t[..10].to_owned())
    } else {
        Some(t.to_owned())
    }
}

fn opt_nonempty(s: &str) -> Option<String> {
    let t = s.trim();
    if t.is_empty() {
        None
    } else {
        Some(t.to_owned())
    }
}

fn has_non_empty(map: &serde_json::Map<String, Value>, key: &str) -> bool {
    match map.get(key) {
        Some(Value::Null) | None => false,
        Some(Value::String(s)) => !s.is_empty(),
        Some(Value::Array(a)) => !a.is_empty(),
        Some(Value::Object(o)) => o.values().any(|v| !matches!(v, Value::Null) && !is_zero(v)),
        _ => true,
    }
}

fn is_zero(v: &Value) -> bool {
    match v {
        Value::Number(n) => n.as_f64() == Some(0.0),
        Value::String(s) => s.is_empty(),
        Value::Bool(b) => !b,
        Value::Array(a) => a.is_empty(),
        Value::Object(o) => o.is_empty(),
        Value::Null => true,
    }
}

fn is_espresso_style(style: &str) -> bool {
    // BC's `style_type` is one of `'POUR OVER'`, `'ESPRESSO'`,
    // `'FULL_IMMERSION'`, `'PERCOLATION'`. Case-insensitive match,
    // tolerant of underscores vs spaces.
    let upper = style.trim().to_ascii_uppercase().replace(' ', "_");
    upper == "ESPRESSO"
}

/// Derive espresso-ness from a BC preparation `type` for pre-v5.2.0 exports
/// that carry no `style_type`. Mirrors `Preparation.getPresetStyleType()`
/// (preparation.ts): these `PREPARATION_TYPES` map to
/// `PREPARATION_STYLE_TYPE.ESPRESSO`; everything else defaults to pour-over.
fn is_espresso_type(prep_type: &str) -> bool {
    matches!(
        prep_type.trim().to_ascii_uppercase().as_str(),
        "CUSTOM_PREPARATION"
            | "PORTAFILTER"
            | "CAFELAT"
            | "FLAIR"
            | "HAND_LEVER"
            | "DECENT_ESPRESSO"
            | "ROK"
            | "METICULOUS"
            | "XENIA"
            | "SANREMO_YOU"
            | "GAGGIUINO"
    )
}

fn brew_duration_ms(b: &BcBrew) -> u64 {
    // Prefer the high-precision `brew_time_milliseconds` (ms float when
    // BC writes it); else fall back to `brew_time` (seconds). Both are
    // optional and may be 0; cap defensively.
    let ms = if b.brew_time_milliseconds > 0.0 {
        b.brew_time_milliseconds
    } else if b.brew_time > 0.0 {
        b.brew_time * 1000.0
    } else {
        0.0
    };
    if ms.is_finite() && ms >= 0.0 && ms < f64::from(u32::MAX) {
        #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
        let v = ms.round() as u64;
        v
    } else {
        0
    }
}

// ── Tests ────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn grind_size_accepts_number_or_string() {
        // Regression (real Beanconqueror v8.6 export): `grind_size` is stored
        // numerically on stepped grinders and as a string elsewhere. A numeric
        // grind_size used to hard-fail the ENTIRE import with
        // "invalid type: integer `5`, expected a string" — losing every bean
        // and brew. All shapes below must now import, stringifying numbers.
        let json = r#"{
            "PREPARATION": [
                {"config":{"uuid":"prep-esp","unix_timestamp":1700000000},"name":"Espresso","style_type":"ESPRESSO"}
            ],
            "BEANS": [
                {"config":{"uuid":"bean-1","unix_timestamp":1700000000},"name":"Test Bean","roaster":"Test Roaster"}
            ],
            "BREWS": [
                {"config":{"uuid":"b-int","unix_timestamp":1700000100},"bean":"bean-1","method_of_preparation":"prep-esp","grind_size":5},
                {"config":{"uuid":"b-str","unix_timestamp":1700000200},"bean":"bean-1","method_of_preparation":"prep-esp","grind_size":"5.5"},
                {"config":{"uuid":"b-float","unix_timestamp":1700000300},"bean":"bean-1","method_of_preparation":"prep-esp","grind_size":7.5},
                {"config":{"uuid":"b-null","unix_timestamp":1700000400},"bean":"bean-1","method_of_preparation":"prep-esp","grind_size":null},
                {"config":{"uuid":"b-absent","unix_timestamp":1700000500},"bean":"bean-1","method_of_preparation":"prep-esp"}
            ]
        }"#;
        let export = parse_export(json).expect("numeric grind_size must not break the parse");
        let plan = bc_to_crema(&export, 1_700_000_000_000, seq_id());
        assert_eq!(plan.shots.len(), 5, "all five espresso brews import");
        let settings: Vec<Option<&str>> = plan
            .shots
            .iter()
            .map(|s| s.stored_shot.metadata.grinder_setting.as_deref())
            .collect();
        // Numbers stringify; null / absent grind_size stays unset.
        assert_eq!(
            settings,
            vec![Some("5"), Some("5.5"), Some("7.5"), None, None]
        );
    }

    #[test]
    fn pre_v5_2_legacy_schema_imports_fully() {
        // A pre-v5.2.0 Beanconqueror export: no `style_type` (derive espresso
        // from `type`), origin in flat `country`/`variety`/`processing`, a
        // single image in `filePath`, yield in legacy `brew_quantity`, numeric
        // fields stored as STRINGS (pre-`fixDataTypes`), and a numeric
        // `grind_size`. Every one of these used to lose data or abort the parse.
        let json = r#"{
            "VERSION": [
                {"config":{"uuid":"v","unix_timestamp":1600000000},"alreadyDisplayedVersions":["4.0.0"]}
            ],
            "PREPARATION": [
                {"config":{"uuid":"prep-pf","unix_timestamp":1600000000},"name":"Portafilter","type":"PORTAFILTER"}
            ],
            "BEANS": [
                {"config":{"uuid":"bean-old","unix_timestamp":1600000000},"name":"Old Bean","roaster":"Old Roaster",
                 "cost":"20","weight":"250","rating":"4",
                 "country":"Colombia","variety":"Caturra","processing":"Washed","filePath":"photo_old.jpg"}
            ],
            "BREWS": [
                {"config":{"uuid":"brew-old","unix_timestamp":1600000100},"bean":"bean-old","method_of_preparation":"prep-pf",
                 "grind_size":5,"brew_temperature":"93","brew_quantity":36,"brew_quantity_type":"gr","brew_time":28}
            ]
        }"#;
        let export =
            parse_export(json).expect("a pre-v5.2 export (string numerics, no style_type) parses");
        let plan = bc_to_crema(&export, 1_700_000_000_000, seq_id());

        // Schema-awareness: source version detected.
        assert_eq!(plan.diagnostics.source_app_version.as_deref(), Some("4.0.0"));

        // Bean: string numerics coerced (D), flat origin recovered (C), filePath
        // photo recovered (E).
        assert_eq!(plan.beans.len(), 1);
        let bean = &plan.beans[0];
        assert_eq!(bean.cost, Some(20.0), "string cost coerced");
        assert_eq!(bean.bag_size, 250.0, "string weight coerced");
        assert_eq!(bean.rating, 4, "string rating coerced");
        assert_eq!(bean.origin.country.as_deref(), Some("Colombia"));
        assert_eq!(bean.origin.variety.as_deref(), Some("Caturra"));
        assert_eq!(bean.origin.processing.as_deref(), Some("Washed"));
        assert!(
            plan.diagnostics
                .bag_photo_filenames
                .iter()
                .any(|f| f == "photo_old.jpg"),
            "filePath recovered as a photo"
        );

        // Brew: espresso derived from PORTAFILTER `type` (A) so the shot is NOT
        // skipped; yield recovered from legacy brew_quantity (B); numeric
        // grind_size stringified (D).
        assert_eq!(plan.shots.len(), 1, "espresso derived from type → shot kept");
        let shot = &plan.shots[0];
        assert_eq!(shot.stored_shot.metadata.yield_out, Some(36.0), "legacy yield");
        assert_eq!(
            shot.stored_shot.metadata.grinder_setting.as_deref(),
            Some("5")
        );
    }

    #[test]
    fn legacy_non_espresso_type_is_skipped() {
        // No style_type, and the `type` (V60) maps to pour-over → the brew is
        // correctly skipped, proving the type-derivation discriminates.
        let json = r#"{
            "PREPARATION": [
                {"config":{"uuid":"prep-v60","unix_timestamp":1600000000},"name":"V60","type":"V60"}
            ],
            "BEANS": [{"config":{"uuid":"b","unix_timestamp":1600000000},"name":"B"}],
            "BREWS": [
                {"config":{"uuid":"br","unix_timestamp":1600000100},"bean":"b","method_of_preparation":"prep-v60","grind_size":"medium"}
            ]
        }"#;
        let export = parse_export(json).expect("parses");
        let plan = bc_to_crema(&export, 1_700_000_000_000, seq_id());
        assert_eq!(plan.shots.len(), 0, "V60 is pour-over → skipped");
        assert_eq!(plan.diagnostics.non_espresso_brews_skipped, 1);
        // No VERSION record → no source version.
        assert_eq!(plan.diagnostics.source_app_version, None);
    }

    fn seq_id() -> impl FnMut() -> String {
        let mut n = 0u32;
        move || {
            n += 1;
            format!("test-{n:04}")
        }
    }

    #[test]
    fn empty_export_parses() {
        let plan = bc_to_crema(&BcExport::default(), 1, seq_id());
        assert_eq!(plan.beans.len(), 0);
        assert_eq!(plan.shots.len(), 0);
    }

    #[test]
    fn roast_enum_accepts_all_known_wire_forms() {
        // Title-case TS values.
        assert_eq!(
            serde_json::from_value::<BcRoast>("City+ Roast".into()).unwrap(),
            BcRoast::CityPlus
        );
        // Proto enum keys.
        assert_eq!(
            serde_json::from_value::<BcRoast>("CITY_PLUS_ROAST".into()).unwrap(),
            BcRoast::CityPlus
        );
        // Suffix-stripped (fixture style).
        assert_eq!(
            serde_json::from_value::<BcRoast>("CITY_PLUS".into()).unwrap(),
            BcRoast::CityPlus
        );
        // Proto integer index.
        assert_eq!(
            serde_json::from_value::<BcRoast>(7.into()).unwrap(),
            BcRoast::CityPlus
        );
        // Unknown garbage falls through.
        assert_eq!(
            serde_json::from_value::<BcRoast>("space mountain".into()).unwrap(),
            BcRoast::Unknown
        );
    }

    #[test]
    fn bean_mix_enum_accepts_wire_forms() {
        assert_eq!(
            serde_json::from_value::<BcBeanMix>("SINGLE_ORIGIN".into()).unwrap(),
            BcBeanMix::SingleOrigin
        );
        assert_eq!(
            serde_json::from_value::<BcBeanMix>("Single Origin".into()).unwrap(),
            BcBeanMix::SingleOrigin
        );
        assert_eq!(
            serde_json::from_value::<BcBeanMix>("Blend".into()).unwrap(),
            BcBeanMix::Blend
        );
    }

    #[test]
    fn beans_import_dedupes_roasters_case_insensitively() {
        let json = r#"{
            "BEANS": [
                {"config":{"uuid":"u1","unix_timestamp":0},"name":"Bean A","roaster":"Onyx"},
                {"config":{"uuid":"u2","unix_timestamp":0},"name":"Bean B","roaster":"onyx"},
                {"config":{"uuid":"u3","unix_timestamp":0},"name":"Bean C","roaster":"Heart"}
            ]
        }"#;
        let export = parse_export(json).unwrap();
        let plan = bc_to_crema(&export, 1_700_000_000_000, seq_id());
        assert_eq!(plan.beans.len(), 3);
        // Two unique roasters; A and B share Onyx, C has Heart.
        assert_eq!(plan.roasters.len(), 2);
        assert_eq!(plan.beans[0].roaster_id, plan.beans[1].roaster_id);
        assert_ne!(plan.beans[0].roaster_id, plan.beans[2].roaster_id);
    }

    #[test]
    fn beans_import_maps_roast_and_mix() {
        let json = r#"{
            "BEANS": [{
                "config":{"uuid":"u1","unix_timestamp":1730000000},
                "name":"Bean","roaster":"R",
                "roast":"FULL_CITY_ROAST",
                "beanMix":"SINGLE_ORIGIN",
                "decaffeinated": true,
                "favourite": true,
                "weight": 250.0,
                "rating": 4.2,
                "cost": 22.5
            }]
        }"#;
        let export = parse_export(json).unwrap();
        let plan = bc_to_crema(&export, 1_700_000_000_000, seq_id());
        let bean = &plan.beans[0];
        assert_eq!(bean.roast_level, Some(6));
        assert_eq!(bean.mix, Some(BeanMix::Single));
        assert!(bean.decaf);
        assert!(bean.favourite);
        assert_eq!(bean.bag_size, 250.0);
        assert_eq!(bean.remaining, 250.0);
        assert_eq!(bean.rating, 4);
        assert_eq!(bean.cost, Some(22.5));
        assert_eq!(bean.beanconqueror_id.as_deref(), Some("u1"));
    }

    #[test]
    fn crema_to_bc_export_basic_shape() {
        use crate::{Bean, Roaster};
        let mut roaster = Roaster::new("roaster:r1".to_owned(), "Onyx".to_owned(), 1);
        roaster.metadata = serde_json::Value::Null;
        let mut bean = Bean::new("bean:b1".to_owned(), "Geisha".to_owned(), 1_700_000_000_000);
        bean.roaster_id = Some("roaster:r1".to_owned());
        bean.bag_size = 250.0;
        bean.remaining = 200.0;
        bean.roast_level = Some(5);
        bean.mix = Some(BeanMix::Single);
        bean.roast_type = Some(BeanRoastType::Espresso);
        bean.cost = Some(22.0);
        bean.tasting_notes = "Peach, jasmine".to_owned();
        bean.metadata = serde_json::json!({
            "beanconqueror": {
                "unmapped": {
                    "ean_article_number": "1234567890",
                    "qr_code": "QR-PAYLOAD"
                },
                "photo_filenames": ["photo_xyz.jpg"]
            }
        });

        let json = crema_to_bc_main_json(&[bean], &[roaster], &[], &[], 1_700_000_000_000);
        let v: serde_json::Value = serde_json::from_str(&json).unwrap();
        let beans = v["BEANS"].as_array().expect("BEANS array");
        assert_eq!(beans.len(), 1);
        let b = &beans[0];
        assert_eq!(b["name"], "Geisha");
        assert_eq!(b["roaster"], "Onyx");
        assert_eq!(b["weight"], 250.0);
        assert_eq!(b["roast"], "CITY_PLUS_ROAST");
        assert_eq!(b["beanMix"], "SINGLE_ORIGIN");
        assert_eq!(b["bean_roasting_type"], "ESPRESSO");
        assert_eq!(b["cost"], 22.0);
        assert_eq!(b["note"], "Peach, jasmine");
        // Unmapped stash restored verbatim onto the bean.
        assert_eq!(b["ean_article_number"], "1234567890");
        assert_eq!(b["qr_code"], "QR-PAYLOAD");
        // Photo filename restored.
        assert_eq!(b["attachments"][0], "photo_xyz.jpg");

        // Synthesised PREPARATION row (ESPRESSO) is present.
        let prep = v["PREPARATION"].as_array().expect("PREPARATION array");
        assert_eq!(prep.len(), 1);
        assert_eq!(prep[0]["style_type"], "ESPRESSO");
    }

    #[test]
    fn unmapped_bc_fields_land_in_metadata_for_round_trip() {
        // Cupping form, EAN, freezing accounting, qr_code, etc. don't
        // have first-class Crema slots — stash them under
        // `metadata.beanconqueror.unmapped` so an export-back-to-BC
        // round-trip doesn't lose data. Two extra blend components
        // (bean_information[1..]) ride under
        // `metadata.beanconqueror.bean_information_extra`.
        let json = r#"{
            "BEANS": [{
                "config":{"uuid":"u1","unix_timestamp":0},
                "name":"x","roaster":"r",
                "cupping_points": "88",
                "ean_article_number": "1234567890",
                "qr_code": "QR-PAYLOAD",
                "frozenStorageType": "BAG",
                "co2e_kg": 1.4,
                "shared": true,
                "cupping": {"body": 5, "notes": "honey"},
                "bean_information": [
                    {"country": "Ethiopia"},
                    {"country": "Colombia", "variety": "Caturra"},
                    {"country": "Kenya"}
                ]
            }]
        }"#;
        let plan = bc_to_crema(&parse_export(json).unwrap(), 1, seq_id());
        let bean = &plan.beans[0];

        // Primary origin still comes from [0].
        assert_eq!(bean.origin.country.as_deref(), Some("Ethiopia"));

        // Round-trip stash holds everything else.
        let meta = bean
            .metadata
            .as_object()
            .expect("metadata is an object")
            .get("beanconqueror")
            .and_then(|v| v.as_object())
            .expect("beanconqueror metadata key");

        // Extras: blend components beyond [0].
        let extras = meta
            .get("bean_information_extra")
            .and_then(|v| v.as_array())
            .expect("bean_information_extra is an array");
        assert_eq!(extras.len(), 2);
        assert_eq!(extras[0]["country"], "Colombia");
        assert_eq!(extras[1]["country"], "Kenya");

        // Unmapped: everything BC ships we don't model. The
        // `_other` flatten catches them, so we get the lot.
        let unmapped = meta
            .get("unmapped")
            .and_then(|v| v.as_object())
            .expect("unmapped catch-all");
        assert_eq!(unmapped["cupping_points"], "88");
        assert_eq!(unmapped["ean_article_number"], "1234567890");
        assert_eq!(unmapped["qr_code"], "QR-PAYLOAD");
        assert_eq!(unmapped["frozenStorageType"], "BAG");
        assert_eq!(unmapped["co2e_kg"], 1.4);
        assert_eq!(unmapped["shared"], true);
        assert_eq!(unmapped["cupping"]["body"], 5);
        assert_eq!(unmapped["cupping"]["notes"], "honey");
    }

    #[test]
    fn finished_bag_sets_remaining_to_zero() {
        // BC's `finished` flag means the bag has been consumed. The bag
        // size stays as a record of how much it held, but `remaining`
        // drops to 0 — Crema's auto-debit signal can't infer this
        // from telemetry, so the import is the only place to land it.
        let json = r#"{
            "BEANS": [
                {"config":{"uuid":"u1","unix_timestamp":0},"name":"a","roaster":"r",
                 "weight": 250.0, "finished": false},
                {"config":{"uuid":"u2","unix_timestamp":0},"name":"b","roaster":"r",
                 "weight": 250.0, "finished": true}
            ]
        }"#;
        let plan = bc_to_crema(&parse_export(json).unwrap(), 1_700_000_000_000, seq_id());
        assert_eq!(plan.beans[0].bag_size, 250.0);
        assert_eq!(plan.beans[0].remaining, 250.0);
        assert_eq!(plan.beans[0].archived_at, None);
        assert_eq!(plan.beans[1].bag_size, 250.0);
        assert_eq!(plan.beans[1].remaining, 0.0);
        assert_eq!(plan.beans[1].archived_at, Some(1_700_000_000_000));
    }

    #[test]
    fn beans_merge_note_and_aromatics_into_tasting_notes() {
        // BC has two free-text fields the user fills in for tasting:
        // `note` (general) and `aromatics` (flavour descriptors).
        // Crema folds both into `tasting_notes` joined by a blank line.
        let json = r#"{
            "BEANS": [
                {"config":{"uuid":"u1","unix_timestamp":0},"name":"a","roaster":"r",
                 "note":"Amazing bean","aromatics":"Sweet, tangy"},
                {"config":{"uuid":"u2","unix_timestamp":0},"name":"b","roaster":"r",
                 "note":"","aromatics":"Yum"},
                {"config":{"uuid":"u3","unix_timestamp":0},"name":"c","roaster":"r",
                 "note":"Only a note","aromatics":""},
                {"config":{"uuid":"u4","unix_timestamp":0},"name":"d","roaster":"r"}
            ]
        }"#;
        let plan = bc_to_crema(&parse_export(json).unwrap(), 1, seq_id());
        assert_eq!(plan.beans[0].tasting_notes, "Amazing bean\n\nSweet, tangy");
        assert_eq!(plan.beans[1].tasting_notes, "Yum");
        assert_eq!(plan.beans[2].tasting_notes, "Only a note");
        assert_eq!(plan.beans[3].tasting_notes, "");
        // `notes` (free-form) stays empty — both BC fields are
        // tasting-focused, neither belongs there.
        assert_eq!(plan.beans[0].notes, "");
    }

    #[test]
    fn roast_range_wins_over_named_roast() {
        let json = r#"{
            "BEANS": [{
                "config":{"uuid":"u1","unix_timestamp":0},
                "name":"x","roaster":"r",
                "roast":"CITY_ROAST", "roast_range": 8
            }]
        }"#;
        let plan = bc_to_crema(&parse_export(json).unwrap(), 1, seq_id());
        // CITY → 4 normally, but roast_range 8 wins.
        assert_eq!(plan.beans[0].roast_level, Some(8));
    }

    #[test]
    fn brews_filter_skips_non_espresso() {
        // Two preparations: one espresso, one V60. Two brews: one each.
        let json = r#"{
            "BEANS": [{
                "config":{"uuid":"bean-1","unix_timestamp":0},
                "name":"Bean","roaster":"R"
            }],
            "PREPARATION": [
                {"config":{"uuid":"prep-esp","unix_timestamp":0},"name":"Espresso","style_type":"ESPRESSO"},
                {"config":{"uuid":"prep-v60","unix_timestamp":0},"name":"V60","style_type":"POUR OVER"}
            ],
            "MILL": [],
            "BREWS": [
                {"config":{"uuid":"b-1","unix_timestamp":1730000000},
                 "bean":"bean-1","method_of_preparation":"prep-esp",
                 "bean_weight_in":18.0,"brew_time":28.0,
                 "brew_beverage_quantity":36.0,"brew_beverage_quantity_type":"GR"},
                {"config":{"uuid":"b-2","unix_timestamp":1730000100},
                 "bean":"bean-1","method_of_preparation":"prep-v60",
                 "bean_weight_in":18.0,"brew_time":180.0}
            ]
        }"#;
        let plan = bc_to_crema(&parse_export(json).unwrap(), 1, seq_id());
        assert_eq!(plan.shots.len(), 1);
        assert_eq!(plan.diagnostics.non_espresso_brews_skipped, 1);
        assert_eq!(plan.diagnostics.shots_imported, 1);

        let shot = &plan.shots[0];
        assert_eq!(shot.stored_shot.completed_at, 1_730_000_000_000); // seconds → ms
        assert_eq!(
            shot.stored_shot.record.duration,
            Duration::from_millis(28_000)
        );
        assert_eq!(shot.stored_shot.metadata.dose, Some(18.0));
        assert_eq!(shot.stored_shot.metadata.yield_out, Some(36.0));
        assert_eq!(shot.bean_name, "Bean");
        assert_eq!(shot.roaster_name.as_deref(), Some("R"));
    }

    #[test]
    fn brews_with_dangling_refs_counted_not_panicked() {
        let json = r#"{
            "BEANS": [],
            "PREPARATION": [],
            "BREWS": [
                {"config":{"uuid":"x","unix_timestamp":0},
                 "bean":"missing","method_of_preparation":"also-missing"},
                {"config":{"uuid":"y","unix_timestamp":0},
                 "bean":"","method_of_preparation":""}
            ]
        }"#;
        let plan = bc_to_crema(&parse_export(json).unwrap(), 1, seq_id());
        assert_eq!(plan.shots.len(), 0);
        assert_eq!(plan.diagnostics.brews_dangling_preparation, 1);
    }

    #[test]
    fn duration_falls_back_seconds_to_ms() {
        // brew_time = 28 s, no high-precision ms. Expect 28_000 ms.
        let json = r#"{
            "BEANS":[{"config":{"uuid":"b","unix_timestamp":0},"name":"x","roaster":"r"}],
            "PREPARATION":[{"config":{"uuid":"p","unix_timestamp":0},"name":"e","style_type":"ESPRESSO"}],
            "BREWS":[{"config":{"uuid":"s","unix_timestamp":0},
                      "bean":"b","method_of_preparation":"p","brew_time":28.0}]
        }"#;
        let plan = bc_to_crema(&parse_export(json).unwrap(), 1, seq_id());
        assert_eq!(
            plan.shots[0].stored_shot.record.duration,
            Duration::from_millis(28_000)
        );
    }

    #[test]
    fn roast_type_maps_to_crema_optional() {
        let json = r#"{
            "BEANS": [
                {"config":{"uuid":"u1","unix_timestamp":0},"name":"a","roaster":"r","bean_roasting_type":"ESPRESSO"},
                {"config":{"uuid":"u2","unix_timestamp":0},"name":"b","roaster":"r","bean_roasting_type":"FILTER"},
                {"config":{"uuid":"u3","unix_timestamp":0},"name":"c","roaster":"r","bean_roasting_type":"OMNI"},
                {"config":{"uuid":"u4","unix_timestamp":0},"name":"d","roaster":"r","bean_roasting_type":"UNKNOWN"},
                {"config":{"uuid":"u5","unix_timestamp":0},"name":"e","roaster":"r"}
            ]
        }"#;
        let plan = bc_to_crema(&parse_export(json).unwrap(), 1, seq_id());
        assert_eq!(plan.beans[0].roast_type, Some(BeanRoastType::Espresso));
        assert_eq!(plan.beans[1].roast_type, Some(BeanRoastType::Filter));
        assert_eq!(plan.beans[2].roast_type, Some(BeanRoastType::Omni));
        // BC `Unknown` → None on the Crema side (per design call).
        assert_eq!(plan.beans[3].roast_type, None);
        // Missing field → None (default).
        assert_eq!(plan.beans[4].roast_type, None);
    }

    /// Read a BC `Beanconqueror.json` out of one of the user's locally
    /// captured fixtures (`~/code/bean-q-slim/Beanconqueror.zip`,
    /// `~/code/bean-q-full-with-photos/Beanconqueror.zip`). Skips the
    /// test cleanly when the file isn't present (CI won't have it).
    fn read_user_fixture(subdir: &str) -> Option<String> {
        let home = std::env::var("HOME").ok()?;
        let zip_path = format!("{home}/code/{subdir}/Beanconqueror.zip");
        if !std::path::Path::new(&zip_path).exists() {
            return None;
        }
        // Shell out to `unzip -p` so we don't add a Rust ZIP dep just
        // for this ignored test.
        let out = std::process::Command::new("unzip")
            .args(["-p", &zip_path, "Beanconqueror.json"])
            .output()
            .ok()?;
        if !out.status.success() {
            return None;
        }
        String::from_utf8(out.stdout).ok()
    }

    /// User's slim export: 2 beans, no attachments, no brews.
    #[test]
    #[ignore = "depends on ~/code/bean-q-slim being present"]
    fn bc_user_slim_fixture() {
        let Some(text) = read_user_fixture("bean-q-slim") else {
            return;
        };
        let plan = bc_to_crema(
            &parse_export(&text).expect("parse"),
            1_700_000_000_000,
            seq_id(),
        );
        assert_eq!(plan.beans.len(), 2, "slim fixture has 2 beans");
        assert_eq!(plan.shots.len(), 0, "slim fixture has no brews");
        assert_eq!(
            plan.diagnostics.bag_photos_referenced, 0,
            "slim fixture has no photo attachments"
        );
    }

    /// Round-trip the user's slim BC fixture: BC → Crema → BC →
    /// Crema, comparing the two ImportPlans. Verifies that the
    /// `metadata.beanconqueror.unmapped` stash preserves everything
    /// BC ships that Crema doesn't model first-class.
    #[test]
    #[ignore = "depends on ~/code/bean-q-slim being present"]
    fn bc_round_trip_slim_fixture_is_lossless() {
        let Some(text1) = read_user_fixture("bean-q-slim") else {
            return;
        };
        let plan1 = bc_to_crema(&parse_export(&text1).unwrap(), 1, seq_id());

        // Round-trip: emit the BC main JSON from the imported plan.
        let mut roasters = plan1.roasters.clone();
        // Stamp deterministic timestamps so the round-trip doesn't
        // diff on `created_at` / `updated_at`.
        for r in roasters.iter_mut() {
            r.created_at = 1;
            r.updated_at = 1;
        }
        let mut beans = plan1.beans.clone();
        for b in beans.iter_mut() {
            b.created_at = 1;
            b.updated_at = 1;
        }
        let bc_json2 = crema_to_bc_main_json(&beans, &roasters, &[], &[], 1);

        // Re-import.
        let plan2 = bc_to_crema(&parse_export(&bc_json2).unwrap(), 1, seq_id());

        // Same counts.
        assert_eq!(
            plan1.beans.len(),
            plan2.beans.len(),
            "bean count round-tripped"
        );
        assert_eq!(
            plan1.roasters.len(),
            plan2.roasters.len(),
            "roaster count round-tripped"
        );

        // Per-bean value-level comparison. Field-by-field (ids /
        // timestamps differ on re-import so we skip them).
        let by_bc_uuid = |plan: &ImportPlan| -> std::collections::HashMap<String, Bean> {
            plan.beans
                .iter()
                .filter_map(|b| b.beanconqueror_id.clone().map(|id| (id, b.clone())))
                .collect()
        };
        let m1 = by_bc_uuid(&plan1);
        let m2 = by_bc_uuid(&plan2);
        for (uuid, b1) in &m1 {
            let b2 = m2.get(uuid).unwrap_or_else(|| {
                panic!("bean with beanconqueror_id {uuid:?} missing after round-trip")
            });
            assert_eq!(b1.name, b2.name, "name");
            assert_eq!(b1.roast_level, b2.roast_level, "roast_level");
            assert_eq!(b1.mix, b2.mix, "mix");
            assert_eq!(b1.roast_type, b2.roast_type, "roast_type");
            assert_eq!(b1.decaf, b2.decaf, "decaf");
            assert_eq!(b1.favourite, b2.favourite, "favourite");
            assert_eq!(b1.bag_size, b2.bag_size, "bag_size");
            assert_eq!(b1.remaining, b2.remaining, "remaining");
            assert_eq!(b1.cost, b2.cost, "cost");
            assert_eq!(b1.rating, b2.rating, "rating");
            assert_eq!(b1.roasted_on, b2.roasted_on, "roasted_on");
            assert_eq!(b1.opened_on, b2.opened_on, "opened_on");
            assert_eq!(b1.frozen_on, b2.frozen_on, "frozen_on");
            assert_eq!(b1.defrosted_on, b2.defrosted_on, "defrosted_on");
            assert_eq!(b1.url, b2.url, "url");
            assert_eq!(b1.tasting_notes, b2.tasting_notes, "tasting_notes");
            assert_eq!(b1.origin, b2.origin, "origin");
            // archivedAt may change on a finished-bag round-trip
            // (now_unix_ms is re-stamped). Compare on presence only.
            assert_eq!(
                b1.archived_at.is_some(),
                b2.archived_at.is_some(),
                "archived"
            );
            // The unmapped stash must round-trip verbatim.
            let stash1 = b1
                .metadata
                .as_object()
                .and_then(|m| m.get("beanconqueror"))
                .and_then(|v| v.get("unmapped"))
                .cloned()
                .unwrap_or(serde_json::Value::Null);
            let stash2 = b2
                .metadata
                .as_object()
                .and_then(|m| m.get("beanconqueror"))
                .and_then(|v| v.get("unmapped"))
                .cloned()
                .unwrap_or(serde_json::Value::Null);
            assert_eq!(stash1, stash2, "metadata.beanconqueror.unmapped");
            let photos1 = b1
                .metadata
                .as_object()
                .and_then(|m| m.get("beanconqueror"))
                .and_then(|v| v.get("photo_filenames"))
                .cloned()
                .unwrap_or(serde_json::Value::Null);
            let photos2 = b2
                .metadata
                .as_object()
                .and_then(|m| m.get("beanconqueror"))
                .and_then(|v| v.get("photo_filenames"))
                .cloned()
                .unwrap_or(serde_json::Value::Null);
            assert_eq!(photos1, photos2, "metadata.beanconqueror.photo_filenames");
        }
    }

    /// Same round-trip on the photo-bearing fixture. The single
    /// `photo_xxx.jpg` attachment must round-trip via
    /// `metadata.beanconqueror.photo_filenames`.
    #[test]
    #[ignore = "depends on ~/code/bean-q-full-with-photos being present"]
    fn bc_round_trip_full_with_photos_fixture_preserves_attachment() {
        let Some(text1) = read_user_fixture("bean-q-full-with-photos") else {
            return;
        };
        let plan1 = bc_to_crema(&parse_export(&text1).unwrap(), 1, seq_id());
        let bc_json2 = crema_to_bc_main_json(&plan1.beans, &plan1.roasters, &[], &[], 1);
        let plan2 = bc_to_crema(&parse_export(&bc_json2).unwrap(), 1, seq_id());

        // Same photo references on each side.
        assert_eq!(
            plan1.diagnostics.bag_photo_filenames,
            plan2.diagnostics.bag_photo_filenames
        );
        assert_eq!(plan1.diagnostics.bag_photos_referenced, 1);
        assert_eq!(plan2.diagnostics.bag_photos_referenced, 1);
        assert_eq!(
            plan2.diagnostics.bag_photo_filenames[0],
            "photo_2325dbb9-2278-47dc-bd81-47872f4c11c0.jpg"
        );
    }

    /// User's full-with-photos export: 2 beans, 1 bean has a photo
    /// attachment (the JPG ships as a sibling of the ZIP on disk),
    /// no brews.
    #[test]
    #[ignore = "depends on ~/code/bean-q-full-with-photos being present"]
    fn bc_user_full_with_photos_fixture() {
        let Some(text) = read_user_fixture("bean-q-full-with-photos") else {
            return;
        };
        let plan = bc_to_crema(
            &parse_export(&text).expect("parse"),
            1_700_000_000_000,
            seq_id(),
        );
        assert_eq!(plan.beans.len(), 2);
        assert_eq!(plan.shots.len(), 0);
        assert_eq!(plan.diagnostics.bag_photos_referenced, 1);
        assert_eq!(
            plan.diagnostics.bag_photo_filenames[0],
            "photo_2325dbb9-2278-47dc-bd81-47872f4c11c0.jpg"
        );
    }

    /// Sanity check against Beanconqueror's bundled test fixture
    /// (`~/code/crema-design/Beanconqueror/src/assets/BeanconquerorTestData.json`).
    /// Ignored by default — the fixture lives outside this repo, so CI
    /// won't have it. Run locally with:
    /// `cargo test -p de1-domain bc_real_fixture_does_not_panic -- --include-ignored`.
    /// The fixture has 1 bean and 2000 brews, all AeroPress (style_type
    /// `FULL_IMMERSION`) — so we expect 1 imported bean, 0 imported shots,
    /// and 2000 non-espresso skips. If the file isn't there the test
    /// silently passes.
    #[test]
    #[ignore = "depends on the BC repo being checked out alongside Crema"]
    fn bc_real_fixture_does_not_panic() {
        let home = match std::env::var("HOME") {
            Ok(h) => h,
            Err(_) => return,
        };
        let path =
            format!("{home}/code/crema-design/Beanconqueror/src/assets/BeanconquerorTestData.json");
        let Ok(text) = std::fs::read_to_string(&path) else {
            // Fixture not present locally — skip.
            return;
        };
        let plan = bc_to_crema(&parse_export(&text).unwrap(), 1_700_000_000_000, seq_id());
        assert_eq!(plan.beans.len(), 1, "fixture has 1 bean");
        assert_eq!(
            plan.shots.len(),
            0,
            "fixture is all AeroPress — no espresso"
        );
        assert!(
            plan.diagnostics.non_espresso_brews_skipped >= 1900,
            "expected ~2000 non-espresso brews counted, got {}",
            plan.diagnostics.non_espresso_brews_skipped
        );
    }

    #[test]
    fn fixture_style_enum_lowercase_unknown_falls_through() {
        // The BC test fixture uses `"roast":"UNKNOWN"` (suffix-stripped
        // SCREAMING_SNAKE) and `"beanMix":"SINGLE_ORIGIN"`. Confirm we
        // accept these without erroring.
        let json = r#"{
            "BEANS": [{
                "config":{"uuid":"u","unix_timestamp":0},
                "name":"x","roaster":"y",
                "roast":"UNKNOWN","beanMix":"SINGLE_ORIGIN"
            }]
        }"#;
        let plan = bc_to_crema(&parse_export(json).unwrap(), 1, seq_id());
        assert_eq!(plan.beans[0].roast_level, None);
        assert_eq!(plan.beans[0].mix, Some(BeanMix::Single));
    }
}

//! The rules that turn a stored row into columns (SPEC §4): every column below
//! the line in `shot`, the row's tags, its search text and its stat inputs.
//!
//! **Two row shapes.** `row_json` is each shell's P3 row as it stands: on web
//! the core's camelCase `StoredShot` shape (`completedAt`, `metadata.dose`,
//! `record.duration`), on Android the Kotlin `StoredShot`'s flat shape
//! (`completedAtMs`, `doseG`, `durationMs`). Both carry `summary`. Each field
//! is read from the web place first, then the Android one, so one rule set
//! serves both and a shot reads the same in either shape.
//!
//! **Total.** Any JSON object derives without a panic, and every value fits
//! the schema (S8): a non-finite number is NULL in a nullable column and 0 in
//! a sort column, and times are rounded to whole ms.

use de1_domain::{
    BrewStatInput, is_custom_method_id, is_espresso_method, normalize_brew_method, ratio_for_method,
};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

use crate::fold::{FIELD_SEPARATOR, fold};
use crate::query::{Live, SortKey, SortValue};

/// The version of these rules. When it moves, the store rewrites every row's
/// derived columns from `row_json` (SPEC §4).
pub const DERIVE_V: u32 = 1;

/// The curated method presets' display labels, keyed by preset id: the one
/// table both shells read (today's `method-labels.ts` and `BrewMethods.kt`).
pub const PRESET_METHOD_LABELS: [(&str, &str); 11] = [
    ("espresso", "Espresso"),
    ("pourover", "V60 / pourover"),
    ("aeropress", "AeroPress"),
    ("french_press", "French press"),
    ("moka", "Moka"),
    ("cold_brew", "Cold brew"),
    ("drip", "Drip machine"),
    ("siphon", "Siphon"),
    ("clever", "Clever / Switch"),
    ("chemex", "Chemex"),
    ("kalita_wave", "Kalita Wave"),
];

/// The label of a curated preset, or `None` for any other method.
#[must_use]
pub fn preset_method_label(method: &str) -> Option<&'static str> {
    let m = method.trim().to_lowercase();
    PRESET_METHOD_LABELS
        .iter()
        .find(|(id, _)| *id == m)
        .map(|(_, label)| *label)
}

/// The label a row shows for its method, without the live custom-method
/// library: empty is "Espresso"; a preset its label; a custom method the label
/// the row snapshotted, else "Custom method"; free text the snapshot, else the
/// text re-humanized ("karlsbad_kanne" is "Karlsbad kanne"). The web's
/// `methodLabel` with no custom-method store; a live custom label comes from
/// `live_method` at query time.
#[must_use]
pub fn method_label(method: Option<&str>, snapshot_label: Option<&str>) -> String {
    let m = method.map(|m| m.trim().to_lowercase()).unwrap_or_default();
    if m.is_empty() {
        return "Espresso".to_owned();
    }
    if let Some(label) = preset_method_label(&m) {
        return label.to_owned();
    }
    let snapshot = snapshot_label.map(str::trim).filter(|s| !s.is_empty());
    if is_custom_method_id(&m) {
        return snapshot.unwrap_or("Custom method").to_owned();
    }
    if let Some(s) = snapshot {
        return s.to_owned();
    }
    let words = m.replace('_', " ");
    let words = words.trim();
    let mut chars = words.chars();
    match chars.next() {
        Some(first) => first.to_uppercase().chain(chars).collect(),
        None => String::new(),
    }
}

/// The grouping key for "the same coffee": roaster and name, each trimmed,
/// lowercased and with whitespace runs collapsed, as `roaster|name`. `None`
/// without a name. The shells' `coffeeKey`.
#[must_use]
pub fn coffee_key(name: Option<&str>, roaster: Option<&str>) -> Option<String> {
    fn norm(s: Option<&str>) -> String {
        s.unwrap_or_default()
            .to_lowercase()
            .split_whitespace()
            .collect::<Vec<_>>()
            .join(" ")
    }
    let n = norm(name);
    if n.is_empty() {
        return None;
    }
    Some(format!("{}|{n}", norm(roaster)))
}

/// The bean label a row shows: "Roaster · Name" when both, else whichever is
/// there, else `None`. Android's `beanLabel` and the web row's bean line.
#[must_use]
pub fn bean_label(name: Option<&str>, roaster: Option<&str>) -> Option<String> {
    let name = name.map(str::trim).unwrap_or_default();
    let roaster = roaster.map(str::trim).unwrap_or_default();
    match (roaster.is_empty(), name.is_empty()) {
        (false, false) => Some(format!("{roaster} · {name}")),
        (false, true) => Some(roaster.to_owned()),
        (true, false) => Some(name.to_owned()),
        (true, true) => None,
    }
}

/// Every derived column of one row (SPEC §4), plus its tags (`shot_tag`).
/// `hold_delete` is state, not derived, and isn't here (S6).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Derived {
    /// [`DERIVE_V`].
    pub derive_v: u32,
    /// When the shot completed, epoch ms.
    pub completed_at: i64,
    /// The tombstone: set means hidden from every list.
    pub deleted_at: Option<i64>,
    /// Pinned.
    pub pinned: bool,
    /// A Brew Log row: `brewMethod` present, whatever its text.
    pub is_brew_log: bool,
    /// The method filter's value: the method trimmed and lowercased,
    /// `"espresso"` when none.
    pub method: String,
    /// The stats family: `None` is espresso.
    pub family: Option<String>,
    /// The profile name, as stored.
    pub profile_name: Option<String>,
    /// The snapshot's `beanId`.
    pub bean_id: Option<String>,
    /// The snapshot's [`coffee_key`].
    pub coffee_key: Option<String>,
    /// The row has tags of its own.
    pub has_tags: bool,
    /// The Visualizer id.
    pub visualizer_id: Option<String>,
    /// The Decent account id.
    pub decent_id: Option<String>,
    /// Machine samples per the summary; `None` is unknown.
    pub sample_count: Option<i64>,
    /// The rating, 0 when unrated.
    pub sort_rating: i64,
    /// The folded profile name, `""` when none.
    pub sort_profile: String,
    /// The folded [`bean_label`], `""` when none.
    pub sort_bean: String,
    /// The yield, 0 when none; finite.
    pub sort_yield: f64,
    /// The duration, ms.
    pub sort_time: i64,
    /// Stat input: the dose, when positive.
    pub st_dose: Option<f64>,
    /// Stat input: the yield (final weight, else peak), when positive.
    pub st_yield: Option<f64>,
    /// Stat input: the method-aware ratio.
    pub st_ratio: Option<f64>,
    /// Stat input: the duration in seconds, when recorded.
    pub st_time_s: Option<f64>,
    /// Stat input: the rating, when rated.
    pub st_rating: Option<f64>,
    /// The folded searchable fields, joined by [`FIELD_SEPARATOR`].
    pub search_text: String,
    /// The row's own tags, without repeats, in the row's order.
    pub tags: Vec<String>,
}

impl Derived {
    /// This row's value for `key`, as a cursor carries it.
    #[must_use]
    pub fn sort_value(&self, key: SortKey) -> SortValue {
        match key {
            SortKey::Date => SortValue::Date,
            SortKey::Rating => SortValue::Int(self.sort_rating),
            SortKey::Profile => SortValue::Text(self.sort_profile.clone()),
            SortKey::Bean => SortValue::Text(self.sort_bean.clone()),
            SortKey::Yield => SortValue::Real(self.sort_yield),
            SortKey::Time => SortValue::Int(self.sort_time),
        }
    }

    /// Whether the row is in lists at all: not tombstoned.
    #[must_use]
    pub fn is_live(&self) -> bool {
        self.deleted_at.is_none()
    }
}

/// Why a row didn't derive.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DeriveError {
    /// `row_json` isn't JSON.
    NotJson(String),
    /// It is JSON, but not an object.
    NotAnObject,
}

impl std::fmt::Display for DeriveError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DeriveError::NotJson(e) => write!(f, "row_json is not JSON: {e}"),
            DeriveError::NotAnObject => f.write_str("row_json is not a JSON object"),
        }
    }
}

impl std::error::Error for DeriveError {}

/// [`derive_value`] over `row_json` text.
///
/// # Errors
/// When the text isn't a JSON object. Every JSON object derives.
pub fn derive_row(row_json: &str) -> Result<Derived, DeriveError> {
    let value: Value =
        serde_json::from_str(row_json).map_err(|e| DeriveError::NotJson(e.to_string()))?;
    derive_value(&value)
}

/// The row's id, as both shapes carry it.
#[must_use]
pub fn row_id(row: &Value) -> Option<&str> {
    row.get("id").and_then(Value::as_str)
}

/// Every derived column of a parsed row.
///
/// # Errors
/// When `row` isn't a JSON object.
pub fn derive_value(row: &Value) -> Result<Derived, DeriveError> {
    let obj = row.as_object().ok_or(DeriveError::NotAnObject)?;
    let r = Row(obj);
    let stat = stat_input_of(&r);

    let brew_method = r.top("brewMethod");
    let method_of = brew_method
        .and_then(Value::as_str)
        .map(|m| m.trim().to_lowercase())
        .filter(|m| !m.is_empty());
    let family = if is_espresso_method(stat.brew_method.as_deref()) {
        None
    } else {
        Some(
            normalize_brew_method(stat.brew_method.as_deref().unwrap_or_default())
                .unwrap_or_default(),
        )
    };

    let bean = r.top("bean").and_then(Value::as_object);
    let bean_str = |k: &str| bean.and_then(|b| b.get(k)).and_then(Value::as_str);
    let bean_name = bean_str("name");
    let bean_roaster = bean_str("roasterName");
    let coffee = bean.and_then(|_| {
        coffee_key(
            Some(bean_name.map(str::trim).unwrap_or_default()),
            bean_roaster.map(str::trim).filter(|r| !r.is_empty()),
        )
    });

    let tags = own_tags(&r);
    let profile_name = r.text("profileName").map(str::to_owned);
    let yield_out = r.number(&["metadata", "yieldOut"], "yieldG");
    let final_weight = r.path(&["summary", "finalWeight"]).and_then(Value::as_f64);
    let sort_yield = finite_or_zero(yield_out.or(final_weight).unwrap_or(0.0));

    let st_yield = yield_of(&stat);
    let deleted_at = r.top("deletedAt").map(|v| v.as_f64().map_or(0, round_ms));

    Ok(Derived {
        derive_v: DERIVE_V,
        completed_at: r
            .number(&["completedAt"], "completedAtMs")
            .map_or(0, round_ms),
        deleted_at,
        pinned: r.top("pinned").and_then(Value::as_bool) == Some(true),
        is_brew_log: brew_method.is_some(),
        method: method_of.clone().unwrap_or_else(|| "espresso".to_owned()),
        family,
        profile_name: profile_name.clone(),
        bean_id: bean_str("beanId").map(str::to_owned),
        coffee_key: coffee,
        has_tags: !tags.is_empty(),
        visualizer_id: r.text("visualizerId").map(str::to_owned),
        decent_id: r.text("decentId").map(str::to_owned),
        sample_count: r
            .path(&["summary", "sampleCount"])
            .and_then(Value::as_f64)
            .filter(|n| n.is_finite())
            .map(round_ms),
        sort_rating: r
            .number(&["metadata", "rating"], "rating")
            .map_or(0, round_ms),
        sort_profile: fold(profile_name.as_deref().unwrap_or_default()),
        sort_bean: fold(&bean_label(bean_name, bean_roaster).unwrap_or_default()),
        sort_yield,
        sort_time: duration_ms(&r).map_or(0, round_ms),
        st_dose: stat
            .dose_g
            .filter(|d| d.is_finite() && *d > 0.0)
            .map(f64::from),
        st_yield: st_yield.map(f64::from),
        st_ratio: ratio_for_method(
            stat.brew_method.as_deref(),
            stat.dose_g,
            stat.water_g,
            st_yield,
        )
        .filter(|r| r.is_finite())
        .map(f64::from),
        st_time_s: time_s(&stat).map(f64::from),
        st_rating: stat.rating.filter(|r| *r > 0).map(f64::from),
        search_text: search_text(&r, method_of.as_deref(), &tags),
        tags,
    })
}

/// The row's input to `brew_history_stats`, as each shell builds it today:
/// web's `brewStatsOf` (final weight is `yieldOut`, else the summary's) and
/// Android's `brewHistoryStats` (`yieldG`, `peakWeightG`).
#[must_use]
pub fn stat_input(row: &Value) -> BrewStatInput {
    match row.as_object() {
        Some(obj) => stat_input_of(&Row(obj)),
        None => BrewStatInput::default(),
    }
}

// The JSON numbers are f64 and the stat inputs f32, as the shells' serde
// round-trip makes them; a value past f32's range becomes infinite and the
// stat rules drop it.
#[allow(clippy::cast_possible_truncation)]
fn stat_input_of(r: &Row<'_>) -> BrewStatInput {
    let f = |web: &[&str], android: &str| r.number(web, android).map(|n| n as f32);
    let final_weight = f(&["metadata", "yieldOut"], "yieldG").or_else(|| {
        r.path(&["summary", "finalWeight"])
            .and_then(Value::as_f64)
            .map(|n| n as f32)
    });
    let peak_weight = r
        .path(&["summary", "peakWeight"])
        .and_then(Value::as_f64)
        .or_else(|| r.top("peakWeightG").and_then(Value::as_f64))
        .map(|n| n as f32);
    BrewStatInput {
        duration_ms: duration_ms(r).map_or(0, |d| u64::try_from(round_ms(d)).unwrap_or(0)),
        final_weight_g: final_weight,
        peak_weight_g: peak_weight,
        dose_g: f(&["metadata", "dose"], "doseG"),
        water_g: f(&["metadata", "waterG"], "waterG"),
        rating: r
            .number(&["metadata", "rating"], "rating")
            .filter(|n| n.fract() == 0.0 && (0.0..=255.0).contains(n))
            .and_then(|n| u8::try_from(round_ms(n)).ok()),
        brew_method: r.text("brewMethod").map(str::to_owned),
    }
}

/// `brew_history_stats`'s yield: final weight, else peak, when positive.
fn yield_of(b: &BrewStatInput) -> Option<f32> {
    b.final_weight_g
        .or(b.peak_weight_g)
        .filter(|y| y.is_finite() && *y > 0.0)
}

/// `brew_history_stats`'s time: seconds, only when recorded.
#[allow(clippy::cast_precision_loss)]
fn time_s(b: &BrewStatInput) -> Option<f32> {
    (b.duration_ms > 0).then(|| b.duration_ms as f32 / 1000.0)
}

fn duration_ms(r: &Row<'_>) -> Option<f64> {
    r.number(&["record", "duration"], "durationMs")
}

/// A time to whole ms: rounded, saturating at i64's ends.
#[allow(clippy::cast_possible_truncation)]
fn round_ms(n: f64) -> i64 {
    if n.is_finite() { n.round() as i64 } else { 0 }
}

fn finite_or_zero(n: f64) -> f64 {
    // -0.0 sorts with 0.0 in SQLite; keep one zero.
    if n.is_finite() && n != 0.0 { n } else { 0.0 }
}

/// The row's own tags: strings, without repeats.
fn own_tags(r: &Row<'_>) -> Vec<String> {
    let mut tags: Vec<String> = Vec::new();
    if let Some(list) = r.top("tags").and_then(Value::as_array) {
        for t in list.iter().filter_map(Value::as_str) {
            if !tags.iter().any(|x| x == t) {
                tags.push(t.to_owned());
            }
        }
    }
    tags
}

/// The folded searchable fields (SPEC §5), joined by [`FIELD_SEPARATOR`].
/// Empty fields are left out.
fn search_text(r: &Row<'_>, method_of: Option<&str>, tags: &[String]) -> String {
    let bean = r.top("bean").and_then(Value::as_object);
    let bean_str = |k: &str| bean.and_then(|b| b.get(k)).and_then(Value::as_str);
    let label = method_label(method_of, r.text("brewMethodLabel"));
    let fields = [
        r.text("profileName"),
        Some(label.as_str()),
        r.text("recipeName"),
        r.meta_text("notes"),
        r.meta_text("nextPlan"),
        r.path(&["metadata", "beans"]).and_then(Value::as_str),
        r.meta_text("barista"),
        r.meta_text("drinker"),
        bean_str("name"),
        bean_str("roasterName"),
        bean_str("grinder"),
        r.text("grinderModel"),
    ];
    let mut out = String::new();
    for f in fields
        .into_iter()
        .flatten()
        .chain(tags.iter().map(String::as_str))
    {
        let folded = fold(f);
        if folded.is_empty() {
            continue;
        }
        if !out.is_empty() {
            out.push(FIELD_SEPARATOR);
        }
        out.push_str(&folded);
    }
    out
}

/// A row in either shape.
struct Row<'a>(&'a Map<String, Value>);

impl<'a> Row<'a> {
    /// A top-level field; `null` is absent.
    fn top(&self, key: &str) -> Option<&'a Value> {
        self.0.get(key).filter(|v| !v.is_null())
    }

    /// A nested field; `null` is absent.
    fn path(&self, path: &[&str]) -> Option<&'a Value> {
        let (first, rest) = path.split_first()?;
        let mut v = self.top(first)?;
        for k in rest {
            v = v.get(k).filter(|v| !v.is_null())?;
        }
        Some(v)
    }

    /// A top-level string.
    fn text(&self, key: &str) -> Option<&'a str> {
        self.top(key).and_then(Value::as_str)
    }

    /// A journal string: `metadata.<key>` on web, `<key>` on Android.
    fn meta_text(&self, key: &str) -> Option<&'a str> {
        self.path(&["metadata", key])
            .and_then(Value::as_str)
            .or_else(|| self.text(key))
    }

    /// A number at the web path, else the Android key.
    fn number(&self, web: &[&str], android: &str) -> Option<f64> {
        self.path(web)
            .and_then(Value::as_f64)
            .or_else(|| self.top(android).and_then(Value::as_f64))
    }
}

/// The live libraries as the store's mirror tables hold them (SPEC §4):
/// `live_bean`, `live_bean_tag` and `live_method`.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct LiveRows {
    /// `(bean_id, coffee_key, name, roaster)`.
    pub beans: Vec<LiveBeanRow>,
    /// `(tag, tag_folded, bean_id)`.
    pub bean_tags: Vec<LiveBeanTagRow>,
    /// `(method, label_folded)`.
    pub methods: Vec<LiveMethodRow>,
}

/// A `live_bean` row.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LiveBeanRow {
    /// The bag's id.
    pub bean_id: String,
    /// [`coffee_key`] of its live name and roaster.
    pub coffee_key: Option<String>,
    /// Its name, trimmed.
    pub name: String,
    /// Its roaster, trimmed; `None` when blank.
    pub roaster: Option<String>,
}

/// A `live_bean_tag` row.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LiveBeanTagRow {
    /// The tag, as the bag holds it.
    pub tag: String,
    /// The tag folded, for search.
    pub tag_folded: String,
    /// The bag's id.
    pub bean_id: String,
}

/// A `live_method` row.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LiveMethodRow {
    /// The method id, trimmed and lowercased.
    pub method: String,
    /// Its label folded, for search.
    pub label_folded: String,
}

/// The mirror rows for `live`. A repeated bag or method keeps its first entry;
/// a repeated tag on one bag is kept once.
#[must_use]
pub fn derive_live(live: &Live) -> LiveRows {
    let mut out = LiveRows::default();
    for b in &live.beans {
        if out.beans.iter().any(|x| x.bean_id == b.bean_id) {
            continue;
        }
        let name = b.name.trim().to_owned();
        let roaster = b
            .roaster
            .as_deref()
            .map(str::trim)
            .filter(|r| !r.is_empty())
            .map(str::to_owned);
        out.beans.push(LiveBeanRow {
            bean_id: b.bean_id.clone(),
            coffee_key: coffee_key(Some(&name), roaster.as_deref()),
            name,
            roaster,
        });
        for t in &b.tags {
            if !out
                .bean_tags
                .iter()
                .any(|x| x.bean_id == b.bean_id && x.tag == *t)
            {
                out.bean_tags.push(LiveBeanTagRow {
                    tag: t.clone(),
                    tag_folded: fold(t),
                    bean_id: b.bean_id.clone(),
                });
            }
        }
    }
    for m in &live.methods {
        let method = m.method.trim().to_lowercase();
        if out.methods.iter().any(|x| x.method == method) {
            continue;
        }
        out.methods.push(LiveMethodRow {
            method,
            label_folded: fold(&m.label),
        });
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn derive(v: &Value) -> Derived {
        derive_value(v).expect("an object derives")
    }

    /// One shot in the web shape.
    fn web_row() -> Value {
        json!({
            "formatVersion": 3,
            "id": "shot:1",
            "completedAt": 1_790_000_000_000_i64,
            "profileName": "Café Crème",
            "metadata": {
                "dose": 18.0, "yieldOut": 36.5, "rating": 4, "notes": "Too SOUR",
                "nextPlan": "go finer", "beans": "Sey · Kenya", "barista": "Zoë",
                "drinker": "Ben", "waterG": null
            },
            "record": { "duration": 28_400 },
            "bean": { "beanId": "bean:1", "name": " Kenya  AA ", "roasterName": "Sey",
                      "grinder": "Niche Zero", "tags": ["bag-tag"] },
            "grinderModel": "EK43",
            "tags": ["dial-in", "dial-in", "Fruity"],
            "visualizerId": "v-1",
            "decentId": "d-1",
            "pinned": true,
            "deletedAt": null,
            "summary": { "v": 1, "sampleCount": 120, "brewSampleCount": 0, "peakWeight": 38.0,
                         "finalWeight": 36.0, "peakPressure": 9.0, "peakTemp": 93.0, "spark": null }
        })
    }

    /// The same shot in Android's shape.
    fn android_row() -> Value {
        json!({
            "id": "shot:1",
            "completedAtMs": 1_790_000_000_000_i64,
            "durationMs": 28_400,
            "yieldG": 36.5,
            "doseG": 18.0,
            "peakWeightG": 38.0,
            "profileName": "Café Crème",
            "bean": { "beanId": "bean:1", "name": " Kenya  AA ", "roasterName": "Sey",
                      "grinder": "Niche Zero", "tags": ["bag-tag"] },
            "rating": 4,
            "notes": "Too SOUR",
            "nextPlan": "go finer",
            "barista": "Zoë",
            "drinker": "Ben",
            "grinderModel": "EK43",
            "tags": ["dial-in", "dial-in", "Fruity"],
            "visualizerId": "v-1",
            "decentId": "d-1",
            "pinned": true,
            "summary": { "v": 1, "sampleCount": 120, "brewSampleCount": 0, "peakWeight": 38.0,
                         "finalWeight": 36.0, "peakPressure": 9.0, "peakTemp": 93.0, "spark": null }
        })
    }

    #[test]
    fn a_web_row_derives_every_column() {
        let d = derive(&web_row());
        assert_eq!(d.derive_v, DERIVE_V);
        assert_eq!(d.completed_at, 1_790_000_000_000);
        assert_eq!(d.deleted_at, None);
        assert!(d.pinned);
        assert!(!d.is_brew_log);
        assert_eq!(d.method, "espresso");
        assert_eq!(d.family, None);
        assert_eq!(d.profile_name.as_deref(), Some("Café Crème"));
        assert_eq!(d.bean_id.as_deref(), Some("bean:1"));
        assert_eq!(d.coffee_key.as_deref(), Some("sey|kenya aa"));
        assert!(d.has_tags);
        assert_eq!(d.tags, vec!["dial-in".to_owned(), "Fruity".to_owned()]);
        assert_eq!(d.visualizer_id.as_deref(), Some("v-1"));
        assert_eq!(d.decent_id.as_deref(), Some("d-1"));
        assert_eq!(d.sample_count, Some(120));
        assert_eq!(d.sort_rating, 4);
        assert_eq!(d.sort_profile, "cafe creme");
        assert_eq!(d.sort_bean, "sey · kenya  aa");
        assert!((d.sort_yield - 36.5).abs() < 1e-9);
        assert_eq!(d.sort_time, 28_400);
        assert_eq!(d.st_dose, Some(18.0));
        assert_eq!(d.st_yield, Some(36.5));
        assert!((d.st_ratio.expect("a ratio") - 36.5 / 18.0).abs() < 1e-6);
        assert_eq!(d.st_time_s, Some(f64::from(28_400_f32 / 1000.0)));
        assert_eq!(d.st_rating, Some(4.0));
        let fields: Vec<&str> = d.search_text.split(FIELD_SEPARATOR).collect();
        assert_eq!(
            fields,
            vec![
                "cafe creme",
                "espresso",
                "too sour",
                "go finer",
                "sey · kenya",
                "zoe",
                "ben",
                " kenya  aa ",
                "sey",
                "niche zero",
                "ek43",
                "dial-in",
                "fruity"
            ]
        );
    }

    #[test]
    fn the_android_shape_derives_as_the_web_shape_does() {
        let web = derive(&web_row());
        let android = derive(&android_row());
        // Android has no legacy flat bean label; nothing else differs.
        assert_eq!(
            android.search_text,
            web.search_text.replace("\u{1F}sey · kenya\u{1F}", "\u{1F}")
        );
        assert_eq!(
            Derived {
                search_text: String::new(),
                ..android
            },
            Derived {
                search_text: String::new(),
                ..web
            }
        );
    }

    #[test]
    fn a_brew_has_its_family_method_and_water_ratio() {
        let d = derive(&json!({
            "id": "b", "completedAt": 5, "brewMethod": " PourOver ", "recipeName": "Hoffmann",
            "metadata": { "dose": 15.0, "waterG": 250.0, "yieldOut": 210.0 },
            "record": { "duration": 0 }
        }));
        assert!(d.is_brew_log);
        assert_eq!(d.method, "pourover");
        assert_eq!(d.family.as_deref(), Some("pourover"));
        assert!((d.st_ratio.expect("ratio") - 250.0 / 15.0).abs() < 1e-5);
        assert_eq!(d.st_time_s, None, "no time recorded");
        assert!(d.search_text.contains("v60 / pourover"));
        assert!(d.search_text.contains("hoffmann"));
    }

    #[test]
    fn an_empty_brew_method_is_a_brew_log_of_espresso() {
        let d = derive(&json!({ "id": "e", "brewMethod": "" }));
        assert!(d.is_brew_log);
        assert_eq!(d.method, "espresso");
        assert_eq!(d.family, None);
    }

    #[test]
    fn a_tombstone_is_hidden_whatever_it_holds() {
        assert_eq!(derive(&json!({ "deletedAt": 7.6 })).deleted_at, Some(8));
        assert_eq!(derive(&json!({ "deletedAt": 0 })).deleted_at, Some(0));
        assert_eq!(derive(&json!({ "deletedAt": "yes" })).deleted_at, Some(0));
        assert_eq!(derive(&json!({ "deletedAt": null })).deleted_at, None);
    }

    #[test]
    fn values_the_schema_refuses_become_null_or_zero() {
        // f32 can't hold 1e300: the stat inputs go non-finite and drop out;
        // sort columns stay finite (S8).
        let d = derive(&json!({
            "completedAt": 1.6, "metadata": { "dose": 1e300, "yieldOut": 1e300, "rating": 4.5 },
            "record": { "duration": 12.5 }, "summary": { "sampleCount": 2.5 }
        }));
        assert_eq!(d.completed_at, 2);
        assert_eq!(d.sort_time, 13);
        assert_eq!(d.st_dose, None);
        assert_eq!(d.st_yield, None);
        assert_eq!(d.st_ratio, None);
        assert_eq!(d.st_rating, None, "a fractional rating isn't a star count");
        assert_eq!(d.sort_rating, 5);
        assert_eq!(d.sample_count, Some(3));
        assert!(d.sort_yield.is_finite());
        let huge = derive(&json!({ "completedAt": -1e300, "metadata": { "yieldOut": -0.0 } }));
        assert_eq!(huge.completed_at, i64::MIN);
        assert_eq!(huge.sort_yield.to_bits(), 0.0_f64.to_bits());
    }

    #[test]
    fn strings_where_numbers_belong_are_absent() {
        let d = derive(&json!({
            "completedAt": "soon", "metadata": { "dose": "18", "rating": "5" }, "tags": [1, "x", null],
            "pinned": "true", "bean": "Kenya", "profileName": 3
        }));
        assert_eq!(d.completed_at, 0);
        assert_eq!(d.st_dose, None);
        assert_eq!(d.sort_rating, 0);
        assert_eq!(d.tags, vec!["x".to_owned()]);
        assert!(!d.pinned);
        assert_eq!(d.bean_id, None);
        assert_eq!(d.coffee_key, None);
        assert_eq!(d.profile_name, None);
    }

    #[test]
    fn a_non_object_is_refused() {
        assert_eq!(derive_row("[]"), Err(DeriveError::NotAnObject));
        assert!(matches!(derive_row("{"), Err(DeriveError::NotJson(_))));
        assert!(derive_row("{}").is_ok());
    }

    #[test]
    fn method_labels_follow_the_web_rule() {
        assert_eq!(method_label(None, None), "Espresso");
        assert_eq!(method_label(Some("  "), None), "Espresso");
        assert_eq!(method_label(Some("French_Press"), None), "French press");
        assert_eq!(method_label(Some("karlsbad_kanne"), None), "Karlsbad kanne");
        assert_eq!(
            method_label(Some("karlsbad_kanne"), Some(" Kanne ")),
            "Kanne"
        );
        assert_eq!(method_label(Some("custom:abc"), None), "Custom method");
        assert_eq!(method_label(Some("custom:abc"), Some("Ibrik")), "Ibrik");
        assert_eq!(method_label(Some("ñandu_brew"), None), "Ñandu brew");
    }

    #[test]
    fn coffee_keys_and_bean_labels() {
        assert_eq!(
            coffee_key(Some("  Kenya   AA "), Some(" Sey ")),
            Some("sey|kenya aa".to_owned())
        );
        assert_eq!(coffee_key(Some("Kenya"), None), Some("|kenya".to_owned()));
        assert_eq!(coffee_key(Some("  "), Some("Sey")), None);
        assert_eq!(
            bean_label(Some(" Kenya "), Some(" Sey ")),
            Some("Sey · Kenya".to_owned())
        );
        assert_eq!(bean_label(Some(""), Some("Sey")), Some("Sey".to_owned()));
        assert_eq!(
            bean_label(Some("Kenya"), Some(" ")),
            Some("Kenya".to_owned())
        );
        assert_eq!(bean_label(None, None), None);
    }

    #[test]
    fn a_bean_without_a_name_has_no_coffee() {
        let d = derive(&json!({ "bean": { "beanId": "b", "roasterName": "Sey" } }));
        assert_eq!(d.bean_id.as_deref(), Some("b"));
        assert_eq!(d.coffee_key, None);
        assert_eq!(d.sort_bean, "sey");
    }

    #[test]
    fn live_rows_mirror_the_libraries() {
        let live = Live {
            beans: vec![
                crate::query::LiveBean {
                    bean_id: "b1".into(),
                    name: " Guji ".into(),
                    roaster: Some(" ".into()),
                    tags: vec!["Floral".into(), "Floral".into()],
                },
                crate::query::LiveBean {
                    bean_id: "b1".into(),
                    name: "Repeat".into(),
                    roaster: None,
                    tags: vec![],
                },
            ],
            methods: vec![crate::query::LiveMethod {
                method: " CUSTOM:x ".into(),
                label: "Ibrík".into(),
            }],
        };
        let rows = derive_live(&live);
        assert_eq!(rows.beans.len(), 1);
        assert_eq!(rows.beans[0].coffee_key.as_deref(), Some("|guji"));
        assert_eq!(rows.beans[0].roaster, None);
        assert_eq!(rows.bean_tags.len(), 1);
        assert_eq!(rows.bean_tags[0].tag_folded, "floral");
        assert_eq!(
            rows.methods,
            vec![LiveMethodRow {
                method: "custom:x".into(),
                label_folded: "ibrik".into()
            }]
        );
    }
}

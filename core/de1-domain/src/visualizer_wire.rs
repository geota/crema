//! # visualizer-wire
//!
//! Bidirectional converters between Crema's [`Bean`] / [`Roaster`] domain
//! types and Visualizer's `/coffee_bags` + `/roasters` REST **wire**
//! shapes, plus the `ShotDetail` → [`WireShot`] / telemetry parsers.
//!
//! Ported from the web shell's `bean/visualizer-sync.ts`
//! (`beanToWire` / `beanFromWire` / `roasterToWire` / `roasterFromWire` /
//! `roastLevelToWire` / `roastLevelFromWire` / `mixFromMetadata`) and
//! `services/shot-sync.ts` (`wireShotFromDetail` /
//! `samplesFromVisualizerDetail`) so every shell shares one byte-identical
//! mapping instead of each re-deriving the gnarly Visualizer wire contract.
//!
//! Sans-IO and deterministic: the shell hands timestamps (`now_ms`) and the
//! already-resolved local roaster id in, and gets plain data out. The
//! wasm / FFI bridges marshal the payload as JSON at the boundary (matching
//! the rest of the `*_json` facade family), so the TS / Kotlin caller can
//! pass its own snake_case Visualizer shapes without type-coupling to the
//! core.
//!
//! **Round-trip discipline.** Crema tucks every Crema-only field into the
//! wire body's `metadata.crema` sub-block (Visualizer round-trips `metadata`
//! losslessly via `additionalProperties: true`), so a push→pull is lossless
//! for the fields Visualizer doesn't model. The roast-level mapping is the
//! one *lossy-by-design* exception (10 Crema levels → 5 Visualizer bands);
//! it is idempotent after the first hop (see [`roast_level_from_wire`]).
//!
//! The `ShotDetail` parsers navigate a loose [`serde_json::Value`] rather
//! than a rigid struct: Visualizer's detail is a two-variant union (its
//! native serializer + the Beanconqueror serializer) read defensively in the
//! TS, and a `Value` walk mirrors the TS's optional-chaining access exactly
//! while never failing to parse.

use crate::bean::{Bean, BeanMix, BeanOrigin, Roaster};
use crate::shot::TimedSample;
use crate::visualizer_sync::WireShot;
use de1_protocol::ShotSample;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use std::time::Duration;

// ── Visualizer wire shapes ───────────────────────────────────────────────
//
// The Crema-side merged shapes the converters produce / consume. Mirror the
// TS `BagWire` / `RoasterWire` in `bean/visualizer-sync.ts` (themselves
// `CoffeeBagDetail` / `RoasterDetail` from the vendored OpenAPI spec, with
// `id` / `name` loosened so the encode side can build a body for a `POST`
// that has no id yet). Snake_case wire keys — this IS the Visualizer JSON.
//
// Optional fields carry `#[serde(default)]` so a server response that omits
// them deserialises cleanly; they serialise as `null` (not omitted) to match
// the TS encode side, which writes an explicit `null` for an unset field.
// `id` is the lone exception — the TS writes `bean.visualizerId ?? undefined`
// (key omitted), so it skips serialising when `None`.

/// Crema-side merged shape for a Visualizer coffee bag — the body
/// [`bean_to_wire`] produces and [`bean_from_wire`] consumes.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct BagWire {
    /// Visualizer bag id — omitted (not `null`) when absent, matching the
    /// TS `bean.visualizerId ?? undefined`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,
    /// Bag name. Required on the wire; the encode side falls back to
    /// `"Untitled bag"`.
    #[serde(default)]
    pub name: String,
    /// The bag's roaster's Visualizer id.
    #[serde(default)]
    pub roaster_id: Option<String>,
    /// ISO `yyyy-mm-dd` roast date.
    #[serde(default)]
    pub roast_date: Option<String>,
    /// ISO `yyyy-mm-dd` frozen date.
    #[serde(default)]
    pub frozen_date: Option<String>,
    /// ISO `yyyy-mm-dd` defrosted date.
    #[serde(default)]
    pub defrosted_date: Option<String>,
    /// Free-text roast band (`"Light"` … `"Dark"`).
    #[serde(default)]
    pub roast_level: Option<String>,
    /// Country of origin.
    #[serde(default)]
    pub country: Option<String>,
    /// Region within the country.
    #[serde(default)]
    pub region: Option<String>,
    /// Farm name.
    #[serde(default)]
    pub farm: Option<String>,
    /// Farmer / producer.
    #[serde(default)]
    pub farmer: Option<String>,
    /// Cultivar / variety.
    #[serde(default)]
    pub variety: Option<String>,
    /// Elevation, free text.
    #[serde(default)]
    pub elevation: Option<String>,
    /// Process, free text.
    #[serde(default)]
    pub processing: Option<String>,
    /// Harvest time, free text.
    #[serde(default)]
    pub harvest_time: Option<String>,
    /// Quality score, free text.
    #[serde(default)]
    pub quality_score: Option<String>,
    /// Tasting notes, free text.
    #[serde(default)]
    pub tasting_notes: Option<String>,
    /// Where the bag was bought.
    #[serde(default)]
    pub place_of_purchase: Option<String>,
    /// Buy-again URL.
    #[serde(default)]
    pub url: Option<String>,
    /// Free-form notes.
    #[serde(default)]
    pub notes: Option<String>,
    /// ISO 8601 archived-at timestamp (Crema concept; round-trips through
    /// the wire as a full datetime string).
    #[serde(default)]
    pub archived_at: Option<String>,
    /// Server-side last-update ISO 8601 timestamp. Read-only on the pull
    /// (the encode side never writes it); a fallback for `bean.updatedAt`.
    #[serde(default)]
    pub updated_at: Option<String>,
    /// Open `metadata` object — the lossless escape valve carrying the
    /// `crema` sub-block. Always an object on the encode side.
    #[serde(default)]
    pub metadata: Option<Value>,
}

/// Crema-side merged shape for a Visualizer roaster — the body
/// [`roaster_to_wire`] produces and [`roaster_from_wire`] consumes.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct RoasterWire {
    /// Visualizer roaster id — omitted when absent (TS `?? undefined`).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,
    /// Roaster name.
    #[serde(default)]
    pub name: String,
    /// Website / store URL.
    #[serde(default)]
    pub website: Option<String>,
    /// Logo / hero image URL.
    #[serde(default)]
    pub image_url: Option<String>,
    /// Canonical-roaster pointer when this row was deduped.
    #[serde(default)]
    pub canonical_roaster_id: Option<String>,
}

// ── Roast-level banding ──────────────────────────────────────────────────

/// Crema's 1..10 roast scale → Visualizer's free-text `roast_level`.
///
/// **Lossy by design**: 10 Crema levels collapse to 5 Visualizer bands.
/// Mirrors the TS `roastLevelToWire`. A `None` level (or non-finite) is
/// `None` out.
#[must_use]
pub fn roast_level_to_wire(level: Option<f64>) -> Option<String> {
    let level = level?;
    if !level.is_finite() {
        return None;
    }
    let band = if level <= 2.0 {
        "Light"
    } else if level <= 4.0 {
        "Medium-Light"
    } else if level <= 6.0 {
        "Medium"
    } else if level <= 8.0 {
        "Medium-Dark"
    } else {
        "Dark"
    };
    Some(band.to_owned())
}

/// Visualizer's free-text `roast_level` → Crema's 1..10 scale.
///
/// Decodes to the in-band representative that keeps the round-trip
/// idempotent after the first hop (GEN1): Light→2, Medium-Light→3,
/// Medium→5, Medium-Dark→7, Dark→9. Unrecognised / empty → `None`.
///
/// The clause order is load-bearing and ported verbatim from the TS — e.g.
/// `"full city"` is caught by the `contains("city")` test (→ 3) before the
/// later `"full city"` clause, so that clause is dead, exactly as in the TS.
#[must_use]
pub fn roast_level_from_wire(label: Option<&str>) -> Option<u8> {
    let label = label?;
    if label.is_empty() {
        return None;
    }
    let norm = label.trim().to_lowercase();
    if norm.contains("cinnamon") || norm == "light" {
        Some(2)
    } else if norm.contains("medium-light") || norm.contains("city") {
        Some(3)
    } else if norm.contains("medium-dark") || norm.contains("full city") {
        Some(7)
    } else if norm.contains("dark") || norm.contains("french") || norm.contains("italian") {
        Some(9)
    } else if norm.contains("medium") {
        Some(5)
    } else {
        None
    }
}

// ── Bean ⇄ wire ──────────────────────────────────────────────────────────

/// Encode a Crema [`Bean`] → Visualizer's POST/PATCH wire body, tucking the
/// Crema-only fields into `metadata.crema`. `roaster_remote_id` is the bag's
/// roaster's Visualizer id (the shell resolves it). Mirrors `beanToWire`.
#[must_use]
pub fn bean_to_wire(bean: &Bean, roaster_remote_id: Option<&str>) -> BagWire {
    let crema = serde_json::json!({
        "crema_id": bean.id,
        "crema_mix": bean.mix,
        "crema_decaf": bean.decaf,
        "crema_favourite": bean.favourite,
        "crema_bag_size_g": bean.bag_size,
        "crema_remaining_g": bean.remaining,
        "crema_rating": bean.rating,
        "crema_grinder": bean.grinder,
        "crema_grinder_setting": bean.grinder_setting,
        "crema_beanconqueror_id": bean.beanconqueror_id,
        "crema_opened_on": bean.opened_on,
        "crema_updated_at": bean.updated_at,
    });
    // Round-trip the user's metadata blob too (lossless escape valve): spread
    // its keys, then add the `crema` block.
    let mut meta = match &bean.metadata {
        Value::Object(map) => map.clone(),
        _ => Map::new(),
    };
    meta.insert("crema".to_owned(), crema);

    BagWire {
        id: bean.visualizer_id.clone(),
        name: if bean.name.is_empty() {
            "Untitled bag".to_owned()
        } else {
            bean.name.clone()
        },
        roaster_id: roaster_remote_id.map(str::to_owned),
        roast_date: bean.roasted_on.clone(),
        frozen_date: bean.frozen_on.clone(),
        defrosted_date: bean.defrosted_on.clone(),
        roast_level: roast_level_to_wire(bean.roast_level.map(f64::from)),
        country: bean.origin.country.clone(),
        region: bean.origin.region.clone(),
        farm: bean.origin.farm.clone(),
        farmer: bean.origin.farmer.clone(),
        variety: bean.origin.variety.clone(),
        elevation: bean.origin.elevation.clone(),
        processing: bean.origin.processing.clone(),
        harvest_time: bean.origin.harvest_time.clone(),
        quality_score: non_empty(&bean.quality_score),
        tasting_notes: non_empty(&bean.tasting_notes),
        place_of_purchase: bean.place_of_purchase.clone(),
        url: bean.url.clone(),
        notes: non_empty(&bean.notes),
        archived_at: bean.archived_at.map(format_iso_datetime_ms),
        updated_at: None,
        metadata: Some(Value::Object(meta)),
    }
}

/// Decode a Visualizer bag → Crema [`Bean`]. `local_roaster_id` is the
/// shell-resolved local roaster id (the TS applies its `roasterIdLookup`
/// closure to `wire.roaster_id` before calling). `fallback_id` is a
/// shell-minted `bean:<uuid>` used only when no `crema_id` rides in the
/// metadata; `now_ms` seeds `created_at` and the `updated_at` fallback.
/// Mirrors `beanFromWire` — including the GEN-class fix that reads
/// `crema_mix` from the `metadata.crema` sub-block (not top-level metadata).
// Sound casts: `crema_bag_size_g` / `crema_remaining_g` are grams (well
// within f32 range — `Bean` itself stores them as f32) and `crema_rating`
// is a 0..5 star count → u8.
#[allow(clippy::cast_possible_truncation)]
#[must_use]
pub fn bean_from_wire(
    wire: &BagWire,
    local_roaster_id: Option<&str>,
    fallback_id: &str,
    now_ms: i64,
) -> Bean {
    // The full user metadata object and its `crema` sub-block.
    let meta = match &wire.metadata {
        Some(Value::Object(map)) => map.clone(),
        _ => Map::new(),
    };
    let crema = match meta.get("crema") {
        Some(Value::Object(map)) => map.clone(),
        _ => Map::new(),
    };

    let id = crema
        .get("crema_id")
        .and_then(Value::as_str)
        .unwrap_or(fallback_id)
        .to_owned();

    let mut bean = Bean::new(id, wire.name.clone(), now_ms);
    bean.visualizer_id = wire.id.clone();
    bean.roaster_id = local_roaster_id.map(str::to_owned);
    bean.roasted_on = wire.roast_date.clone();
    bean.frozen_on = wire.frozen_date.clone();
    bean.defrosted_on = wire.defrosted_date.clone();
    bean.opened_on = crema
        .get("crema_opened_on")
        .and_then(Value::as_str)
        .map(str::to_owned);
    bean.roast_level = roast_level_from_wire(wire.roast_level.as_deref());
    // GEN fix: read `crema_mix` from the `crema` sub-block where
    // `bean_to_wire` writes it (the old TS read top-level metadata, so `mix`
    // silently never round-tripped).
    bean.mix = crema.get("crema_mix").and_then(mix_from_value);
    bean.decaf = crema.get("crema_decaf") == Some(&Value::Bool(true));
    bean.origin = BeanOrigin {
        country: wire.country.clone(),
        region: wire.region.clone(),
        farm: wire.farm.clone(),
        farmer: wire.farmer.clone(),
        variety: wire.variety.clone(),
        elevation: wire.elevation.clone(),
        processing: wire.processing.clone(),
        harvest_time: wire.harvest_time.clone(),
    };
    bean.bag_size = crema
        .get("crema_bag_size_g")
        .and_then(Value::as_f64)
        .map_or(0.0, |n| n as f32);
    bean.remaining = crema
        .get("crema_remaining_g")
        .and_then(Value::as_f64)
        .map_or(0.0, |n| n as f32);
    bean.quality_score = wire.quality_score.clone().unwrap_or_default();
    bean.tasting_notes = wire.tasting_notes.clone().unwrap_or_default();
    bean.rating = crema
        .get("crema_rating")
        .and_then(Value::as_f64)
        .filter(|n| n.is_finite())
        .map_or(0, |n| n as u8);
    bean.place_of_purchase = wire.place_of_purchase.clone();
    bean.url = wire.url.clone();
    bean.notes = wire.notes.clone().unwrap_or_default();
    bean.favourite = crema.get("crema_favourite") == Some(&Value::Bool(true));
    bean.archived_at = wire.archived_at.as_deref().and_then(parse_iso_datetime_ms);
    bean.grinder = crema
        .get("crema_grinder")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_owned();
    bean.grinder_setting = crema
        .get("crema_grinder_setting")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_owned();
    bean.beanconqueror_id = crema
        .get("crema_beanconqueror_id")
        .and_then(Value::as_str)
        .map(str::to_owned);
    bean.updated_at = crema
        .get("crema_updated_at")
        .and_then(Value::as_i64)
        .or_else(|| wire.updated_at.as_deref().and_then(parse_iso_datetime_ms))
        .unwrap_or(now_ms);
    // Keep the Crema-only block out of the visible blob.
    let mut visible = meta;
    visible.remove("crema");
    bean.metadata = Value::Object(visible);
    bean
}

/// `crema_mix` value (`"single"` / `"blend"`) → [`BeanMix`], else `None`.
/// Mirrors the TS `mixFromMetadata`.
fn mix_from_value(v: &Value) -> Option<BeanMix> {
    match v.as_str() {
        Some("single") => Some(BeanMix::Single),
        Some("blend") => Some(BeanMix::Blend),
        _ => None,
    }
}

// ── Roaster ⇄ wire ───────────────────────────────────────────────────────

/// Encode a Crema [`Roaster`] → Visualizer's wire body. The Crema-only
/// `city` field stays local (Visualizer doesn't model it). Mirrors
/// `roasterToWire`.
#[must_use]
pub fn roaster_to_wire(roaster: &Roaster) -> RoasterWire {
    RoasterWire {
        id: roaster.visualizer_id.clone(),
        name: roaster.name.clone(),
        website: roaster.website.clone(),
        image_url: roaster.image_url.clone(),
        canonical_roaster_id: roaster.canonical_roaster_id.clone(),
    }
}

/// Decode a Visualizer roaster → Crema [`Roaster`]. `fallback_id` is a
/// shell-minted `roaster:<uuid>`; `now_ms` seeds the timestamps. Mirrors
/// `roasterFromWire` (which builds a `blankRoaster` then overlays the wire
/// fields — so unset Crema-only fields keep their blank defaults).
#[must_use]
pub fn roaster_from_wire(wire: &RoasterWire, fallback_id: &str, now_ms: i64) -> Roaster {
    let mut roaster = Roaster::new(fallback_id.to_owned(), wire.name.clone(), now_ms);
    roaster.visualizer_id = wire.id.clone();
    roaster.website = wire.website.clone();
    roaster.image_url = wire.image_url.clone();
    roaster.canonical_roaster_id = wire.canonical_roaster_id.clone();
    roaster
}

// ── ShotDetail → WireShot / telemetry ────────────────────────────────────
//
// These read Visualizer's two-variant `ShotDetail` (its native serializer
// flattens scoring + journal fields onto the row; the Beanconqueror variant
// nests them under `meta.visualizer`) defensively from a loose `Value`.

/// Convert a Visualizer `ShotSummary` (`id` + unix-**sec** `clock` /
/// `updated_at`) plus the full `ShotDetail` body into Crema's [`WireShot`]
/// (unix **ms**, normalised field names). Mirrors `wireShotFromDetail`.
// Sound casts: `duration` (sec) × 1000 → ms `i64`; `drink_weight` is grams
// → f32; `flavor`/3 is clamped to 0..5 → i32. Out-of-range floats saturate
// (Rust `as`), which the subsequent clamp / use tolerates.
#[allow(clippy::cast_possible_truncation)]
#[must_use]
pub fn wire_shot_from_detail(
    summary_id: &str,
    summary_clock_sec: i64,
    summary_updated_at_sec: i64,
    detail: &Value,
) -> WireShot {
    let bc = detail.pointer("/meta/visualizer");
    let bc_get = |key: &str| bc.and_then(|m| m.get(key));

    let profile_title = detail.get("profile_title").and_then(value_as_string);

    // `duration` is seconds on both variants; → ms.
    let duration_sec = detail
        .get("duration")
        .and_then(Value::as_f64)
        .or_else(|| bc_get("duration").and_then(Value::as_f64));
    let duration_ms = duration_sec.map_or(0, |s| (s * 1000.0).round() as i64);

    // `drink_weight` is a stringified gram value in the default response.
    let final_weight_g = detail
        .get("drink_weight")
        .and_then(js_number_finite)
        .map(|n| n as f32);

    let espresso_notes = detail
        .get("private_notes")
        .and_then(value_as_string)
        .or_else(|| bc_get("private_notes").and_then(value_as_string))
        .or_else(|| detail.get("espresso_notes").and_then(value_as_string));

    // Crema rating is 0..5; the wire's cupping slots are 0..15. The PATCH
    // writes `flavor = rating * 3`, so the pull does the inverse: prefer
    // `flavor`, fall back to the legacy `espresso_enjoyment`, then map back.
    let flavor_raw = detail
        .get("flavor")
        .and_then(Value::as_f64)
        .or_else(|| bc_get("flavor").and_then(Value::as_f64))
        .or_else(|| detail.get("espresso_enjoyment").and_then(Value::as_f64))
        .or_else(|| bc_get("espresso_enjoyment").and_then(Value::as_f64));
    let rating = flavor_raw.map(|f| ((f / 3.0).round() as i32).clamp(0, 5));

    // Read-side detail field is `tags`; the BC variant carries none.
    let tag_list: Vec<String> = match detail.get("tags") {
        Some(Value::Array(arr)) => arr
            .iter()
            .filter_map(|t| t.as_str().map(str::to_owned))
            .collect(),
        _ => Vec::new(),
    };

    WireShot {
        id: summary_id.to_owned(),
        clock: summary_clock_sec * 1000,
        duration_ms,
        profile_title,
        final_weight_g,
        notes: espresso_notes,
        rating,
        updated_at_ms: Some(summary_updated_at_sec * 1000),
        tag_list,
    }
}

/// Reconstruct Crema's per-sample telemetry from a Visualizer shot detail.
///
/// Visualizer stores the curve as parallel columns — a `timeframe` axis
/// (seconds) plus one array per channel — zipped by index into the
/// row-shaped [`TimedSample`] the history chart consumes. The only
/// conversion is the time axis (seconds → ms). A detail with no time axis (a
/// Beanconqueror row, or a curveless shot) yields `[]`. Mirrors
/// `samplesFromVisualizerDetail`.
// Sound casts: telemetry channel values → f32 (the chart's native
// precision); `timeframe` (sec) × 1000 → ms `u64` for `Duration` (a
// negative time saturates to 0, which the chart tolerates).
#[allow(clippy::cast_possible_truncation)]
#[must_use]
pub fn samples_from_visualizer_detail(detail: &Value) -> Vec<TimedSample> {
    let time = match detail.get("timeframe") {
        Some(Value::Array(arr)) if !arr.is_empty() => arr,
        _ => return Vec::new(),
    };
    let data = detail.get("data");
    let channel = |name: &str| -> Option<&Vec<Value>> {
        data.and_then(|d| d.get(name)).and_then(Value::as_array)
    };

    // `num`: index a channel, coerce JS-`Number`-style, non-finite → 0.
    let num = |arr: Option<&Vec<Value>>, i: usize| -> f32 {
        arr.and_then(|a| a.get(i))
            .and_then(js_number_finite)
            .unwrap_or(0.0) as f32
    };
    // `opt`: like `num`, but a non-positive reading means "no signal" → None.
    let opt = |arr: Option<&Vec<Value>>, i: usize| -> Option<f32> {
        let n = num(arr, i);
        if n > 0.0 { Some(n) } else { None }
    };

    let pressure = channel("espresso_pressure");
    let flow = channel("espresso_flow");
    let weight = channel("espresso_weight");
    let flow_weight = channel("espresso_flow_weight");
    let pressure_goal = channel("espresso_pressure_goal");
    let flow_goal = channel("espresso_flow_goal");
    let temp_goal = channel("espresso_temperature_goal");
    let temp_mix = channel("espresso_temperature_mix");
    let temp_basket = channel("espresso_temperature_basket");
    let water = channel("espresso_water_dispensed");

    let mut out = Vec::with_capacity(time.len());
    for i in 0..time.len() {
        let t_sec = num(Some(time), i);
        out.push(TimedSample {
            elapsed: Duration::from_millis((f64::from(t_sec) * 1000.0).round() as u64),
            sample: ShotSample {
                sample_time: 0,
                group_pressure: num(pressure, i),
                group_flow: num(flow, i),
                head_temp: num(temp_basket, i),
                mix_temp: num(temp_mix, i),
                set_head_temp: num(temp_goal, i),
                set_mix_temp: 0.0,
                set_group_pressure: num(pressure_goal, i),
                set_group_flow: num(flow_goal, i),
                frame_number: 0,
                steam_temp: 0.0,
            },
            scale_weight: opt(weight, i),
            scale_flow_weight: opt(flow_weight, i),
            dispensed_volume: opt(water, i),
            resistance: None,
            resistance_weight: None,
        });
    }
    out
}

// ── JS-number coercion ───────────────────────────────────────────────────

/// JS `Number(v)` with a finiteness gate: returns `Some(n)` when `v` coerces
/// to a finite number, else `None`. Matches the TS boundary's
/// `typeof v === 'number' ? v : Number(v)` followed by `Number.isFinite`.
///
/// - number → itself · bool → 1/0 · null → 0
/// - string → trimmed; `""` → 0, else parse (non-numeric → `None`)
/// - array / object → `None`
fn js_number_finite(v: &Value) -> Option<f64> {
    let n = match v {
        Value::Number(n) => n.as_f64()?,
        Value::Bool(b) => {
            if *b {
                1.0
            } else {
                0.0
            }
        }
        Value::Null => 0.0,
        Value::String(s) => {
            let t = s.trim();
            if t.is_empty() {
                0.0
            } else {
                t.parse::<f64>().ok()?
            }
        }
        _ => return None,
    };
    n.is_finite().then_some(n)
}

/// `Value::as_str` → owned `String`, mirroring the TS `?? null` access that
/// only accepts a real string (a non-string field reads as absent).
fn value_as_string(v: &Value) -> Option<String> {
    v.as_str().map(str::to_owned)
}

/// `bean.qualityScore || null` — an empty string is falsy → `None`.
fn non_empty(s: &str) -> Option<String> {
    if s.is_empty() {
        None
    } else {
        Some(s.to_owned())
    }
}

// ── ISO 8601 datetime ⇄ unix ms ──────────────────────────────────────────
//
// `bean.archivedAt` round-trips as the JS `new Date(ms).toISOString()` form
// (`YYYY-MM-DDTHH:MM:SS.sssZ`, always UTC). The decode side mirrors
// `Date.parse` for that canonical shape (plus a couple of lenient variants
// the server might emit).

const MS_PER_DAY: i64 = 86_400_000;

/// Days since the Unix epoch for a UTC civil date (Howard Hinnant's
/// `days_from_civil`). Returns `None` only on `i64` overflow.
fn days_from_civil(y: i64, m: i64, d: i64) -> Option<i64> {
    let y = if m <= 2 { y - 1 } else { y };
    let era = if y >= 0 { y } else { y - 399 }.div_euclid(400);
    let yoe = y - era * 400;
    let doy = (153 * (m + if m > 2 { -3 } else { 9 }) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era.checked_mul(146_097)?
        .checked_add(doe)?
        .checked_sub(719_468)
}

/// Inverse of [`days_from_civil`] — a day count since the epoch back to a
/// `(year, month, day)` UTC civil date.
fn civil_from_days(z: i64) -> (i64, i64, i64) {
    let z = z + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 }.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (if m <= 2 { y + 1 } else { y }, m, d)
}

/// Unix epoch ms → `YYYY-MM-DDTHH:MM:SS.sssZ` (UTC), matching JS
/// `new Date(ms).toISOString()`.
#[must_use]
pub fn format_iso_datetime_ms(ms: i64) -> String {
    let day = ms.div_euclid(MS_PER_DAY);
    let mut rem = ms.rem_euclid(MS_PER_DAY); // [0, 86_400_000)
    let (y, mo, d) = civil_from_days(day);
    let millis = rem % 1000;
    rem /= 1000;
    let secs = rem % 60;
    rem /= 60;
    let mins = rem % 60;
    let hours = rem / 60;
    format!("{y:04}-{mo:02}-{d:02}T{hours:02}:{mins:02}:{secs:02}.{millis:03}Z")
}

/// `YYYY-MM-DDTHH:MM:SS(.sss)?(Z|±HH:MM)?` → unix epoch ms. Mirrors
/// `Date.parse` for the canonical `toISOString` output plus the common
/// no-millis / offset variants. Returns `None` on anything it can't parse
/// (→ a `null` `archivedAt`, matching the TS `wire.archived_at ? … : null`).
#[must_use]
pub fn parse_iso_datetime_ms(s: &str) -> Option<i64> {
    let s = s.trim();
    let bytes = s.as_bytes();
    // Date part: `YYYY-MM-DD`.
    if bytes.len() < 10 || bytes[4] != b'-' || bytes[7] != b'-' {
        return None;
    }
    let year: i64 = s.get(0..4)?.parse().ok()?;
    let month: i64 = s.get(5..7)?.parse().ok()?;
    let day: i64 = s.get(8..10)?.parse().ok()?;
    if !(1..=12).contains(&month) || !(1..=31).contains(&day) {
        return None;
    }
    let date_ms = days_from_civil(year, month, day)?.checked_mul(MS_PER_DAY)?;

    let rest = &s[10..];
    if rest.is_empty() {
        return Some(date_ms);
    }
    // Time part follows a `T` (or space) separator.
    let sep = rest.as_bytes()[0];
    if sep != b'T' && sep != b't' && sep != b' ' {
        return None;
    }
    let mut time = &rest[1..];

    // Trailing timezone: `Z` or `±HH:MM` / `±HHMM`. We only honour `Z` /
    // `+00:00` (UTC) — a non-UTC offset is parsed but its minutes applied.
    let mut offset_min: i64 = 0;
    if let Some(stripped) = time.strip_suffix('Z').or_else(|| time.strip_suffix('z')) {
        time = stripped;
    } else if let Some(idx) = time.rfind(['+', '-']) {
        // Guard: the `-`/`+` must be in the timezone tail, not part of time.
        let (head, tz) = time.split_at(idx);
        let sign = if tz.starts_with('-') { -1 } else { 1 };
        let tz = &tz[1..].replace(':', "");
        if tz.len() == 4 {
            let oh: i64 = tz.get(0..2)?.parse().ok()?;
            let om: i64 = tz.get(2..4)?.parse().ok()?;
            offset_min = sign * (oh * 60 + om);
            time = head;
        }
    }

    let mut parts = time.split(':');
    let hours: i64 = parts.next()?.parse().ok()?;
    let mins: i64 = parts.next().unwrap_or("0").parse().ok()?;
    let sec_field = parts.next().unwrap_or("0");
    let (secs, millis): (i64, i64) = match sec_field.split_once('.') {
        Some((s, frac)) => {
            // Pad / truncate the fraction to exactly 3 digits (ms).
            let mut f = frac.to_owned();
            f.truncate(3);
            while f.len() < 3 {
                f.push('0');
            }
            (s.parse().ok()?, f.parse().ok()?)
        }
        None => (sec_field.parse().ok()?, 0),
    };
    let time_ms = ((hours * 60 + mins) * 60 + secs) * 1000 + millis - offset_min * 60_000;
    Some(date_ms + time_ms)
}

// ── JSON facades for the wasm / FFI bridges ──────────────────────────────

/// JSON-bridged [`bean_to_wire`]. Input: a [`Bean`] JSON + the roaster's
/// remote id. Output: the [`BagWire`] JSON.
///
/// # Errors
/// The JSON parse / serialise error string on a malformed `bean_json`.
pub fn bean_to_wire_json(
    bean_json: &str,
    roaster_remote_id: Option<&str>,
) -> Result<String, String> {
    let bean: Bean = serde_json::from_str(bean_json).map_err(|e| e.to_string())?;
    let wire = bean_to_wire(&bean, roaster_remote_id);
    serde_json::to_string(&wire).map_err(|e| e.to_string())
}

/// JSON-bridged [`bean_from_wire`]. Input: a [`BagWire`] JSON. Output: the
/// decoded [`Bean`] JSON.
///
/// # Errors
/// The JSON parse / serialise error string on a malformed `wire_json`.
pub fn bean_from_wire_json(
    wire_json: &str,
    local_roaster_id: Option<&str>,
    fallback_id: &str,
    now_ms: i64,
) -> Result<String, String> {
    let wire: BagWire = serde_json::from_str(wire_json).map_err(|e| e.to_string())?;
    let bean = bean_from_wire(&wire, local_roaster_id, fallback_id, now_ms);
    serde_json::to_string(&bean).map_err(|e| e.to_string())
}

/// JSON-bridged [`roaster_to_wire`].
///
/// # Errors
/// The JSON parse / serialise error string on a malformed `roaster_json`.
pub fn roaster_to_wire_json(roaster_json: &str) -> Result<String, String> {
    let roaster: Roaster = serde_json::from_str(roaster_json).map_err(|e| e.to_string())?;
    let wire = roaster_to_wire(&roaster);
    serde_json::to_string(&wire).map_err(|e| e.to_string())
}

/// JSON-bridged [`roaster_from_wire`].
///
/// # Errors
/// The JSON parse / serialise error string on a malformed `wire_json`.
pub fn roaster_from_wire_json(
    wire_json: &str,
    fallback_id: &str,
    now_ms: i64,
) -> Result<String, String> {
    let wire: RoasterWire = serde_json::from_str(wire_json).map_err(|e| e.to_string())?;
    let roaster = roaster_from_wire(&wire, fallback_id, now_ms);
    serde_json::to_string(&roaster).map_err(|e| e.to_string())
}

/// JSON-bridged [`wire_shot_from_detail`]. Input:
/// `{"summary": {"id", "clock", "updated_at"}, "detail": <ShotDetail>}`
/// (`clock` / `updated_at` are unix **sec**). Output: the [`WireShot`] JSON.
///
/// # Errors
/// The JSON parse / serialise error string on malformed input.
pub fn wire_shot_from_detail_json(payload: &str) -> Result<String, String> {
    #[derive(Deserialize)]
    struct Summary {
        id: String,
        clock: i64,
        updated_at: i64,
    }
    #[derive(Deserialize)]
    struct In {
        summary: Summary,
        detail: Value,
    }
    let inp: In = serde_json::from_str(payload).map_err(|e| e.to_string())?;
    let wire = wire_shot_from_detail(
        &inp.summary.id,
        inp.summary.clock,
        inp.summary.updated_at,
        &inp.detail,
    );
    serde_json::to_string(&wire).map_err(|e| e.to_string())
}

/// JSON-bridged [`samples_from_visualizer_detail`]. Input: a `ShotDetail`
/// JSON. Output: a `TimedSample[]` JSON.
///
/// # Errors
/// The JSON parse / serialise error string on a malformed `detail_json`.
pub fn samples_from_visualizer_detail_json(detail_json: &str) -> Result<String, String> {
    let detail: Value = serde_json::from_str(detail_json).map_err(|e| e.to_string())?;
    let samples = samples_from_visualizer_detail(&detail);
    serde_json::to_string(&samples).map_err(|e| e.to_string())
}

// ── Tests ────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    // ── roast-level banding (GEN1) ─────────────────────────────────────

    #[test]
    fn roast_level_lands_every_level_on_an_in_band_rep() {
        let reps = [2u8, 3, 5, 7, 9];
        for lvl in 1..=10 {
            let back = roast_level_from_wire(roast_level_to_wire(Some(f64::from(lvl))).as_deref())
                .unwrap();
            assert!(reps.contains(&back), "level {lvl} → {back}");
        }
    }

    #[test]
    fn roast_level_is_idempotent_after_first_hop() {
        for lvl in 1..=10 {
            let once = roast_level_from_wire(roast_level_to_wire(Some(f64::from(lvl))).as_deref())
                .unwrap();
            let twice =
                roast_level_from_wire(roast_level_to_wire(Some(f64::from(once))).as_deref())
                    .unwrap();
            assert_eq!(once, twice, "level {lvl}");
        }
    }

    #[test]
    fn roast_level_maps_canonical_labels_medium_dark_to_7() {
        assert_eq!(roast_level_from_wire(Some("Light")), Some(2));
        assert_eq!(roast_level_from_wire(Some("Medium-Light")), Some(3));
        assert_eq!(roast_level_from_wire(Some("Medium")), Some(5));
        assert_eq!(roast_level_from_wire(Some("Medium-Dark")), Some(7));
        assert_eq!(roast_level_from_wire(Some("Dark")), Some(9));
    }

    #[test]
    fn roast_level_null_for_unset_or_unrecognised() {
        assert_eq!(roast_level_to_wire(None), None);
        assert_eq!(roast_level_from_wire(None), None);
        assert_eq!(roast_level_from_wire(Some("")), None);
        assert_eq!(roast_level_from_wire(Some("chartreuse")), None);
    }

    // ── bean wire round-trip (mirrors the TS vitest) ───────────────────

    fn round_trip_bean() -> Bean {
        let mut bean = Bean::new(
            "bean-1".to_owned(),
            "Yirgacheffe".to_owned(),
            1_700_000_000_000,
        );
        bean.roaster_id = Some("r-local".to_owned());
        bean.roasted_on = Some("2026-05-01".to_owned());
        bean.roast_level = Some(5); // a band rep, survives losslessly
        bean.mix = Some(BeanMix::Single);
        bean.decaf = true;
        bean.favourite = true;
        bean.bag_size = 250.0;
        bean.remaining = 137.0;
        bean.grinder = "Niche Zero".to_owned();
        bean.grinder_setting = "18".to_owned();
        bean.origin = BeanOrigin {
            country: Some("Ethiopia".to_owned()),
            variety: Some("Heirloom".to_owned()),
            ..BeanOrigin::default()
        };
        bean.notes = "floral".to_owned();
        bean.beanconqueror_id = Some("bc-9".to_owned());
        bean.metadata = serde_json::json!({ "custom": "keep-me" });
        bean
    }

    #[test]
    fn bean_round_trips_through_metadata_crema() {
        let bean = round_trip_bean();
        let wire = bean_to_wire(&bean, Some("rv-1"));
        // Local lookup: rv-1 → r-local (mirrors the TS closure).
        let back = bean_from_wire(&wire, Some("r-local"), "bean-fallback", 1_700_000_000_000);

        assert_eq!(back.name, "Yirgacheffe");
        assert_eq!(back.roaster_id.as_deref(), Some("r-local"));
        assert_eq!(back.roasted_on.as_deref(), Some("2026-05-01"));
        assert_eq!(back.roast_level, Some(5));
        assert_eq!(back.mix, Some(BeanMix::Single));
        assert!(back.decaf);
        assert!(back.favourite);
        assert_eq!(back.bag_size, 250.0);
        assert_eq!(back.remaining, 137.0);
        assert_eq!(back.grinder, "Niche Zero");
        assert_eq!(back.grinder_setting, "18");
        assert_eq!(back.origin.country.as_deref(), Some("Ethiopia"));
        assert_eq!(back.origin.variety.as_deref(), Some("Heirloom"));
        assert_eq!(back.notes, "floral");
        assert_eq!(back.beanconqueror_id.as_deref(), Some("bc-9"));
        // The user metadata blob round-trips; the Crema-only block is stripped.
        assert_eq!(back.metadata, serde_json::json!({ "custom": "keep-me" }));
    }

    #[test]
    fn bean_uses_crema_id_when_present_else_fallback() {
        let bean = round_trip_bean();
        let wire = bean_to_wire(&bean, None);
        let back = bean_from_wire(&wire, None, "bean-fallback", 0);
        // `crema_id` rode through the metadata → the original id wins.
        assert_eq!(back.id, "bean-1");

        // A wire with no `crema` block falls back to the shell-minted id.
        let bare = BagWire {
            name: "x".to_owned(),
            ..BagWire::default()
        };
        let decoded = bean_from_wire(&bare, None, "bean-fallback", 0);
        assert_eq!(decoded.id, "bean-fallback");
    }

    #[test]
    fn bean_mix_round_trips_from_the_crema_block() {
        // GEN regression: a single/blend mix must survive the round-trip
        // (the old TS read the wrong metadata path so `mix` came back null).
        for mix in [BeanMix::Single, BeanMix::Blend] {
            let mut bean = round_trip_bean();
            bean.mix = Some(mix);
            let wire = bean_to_wire(&bean, None);
            let back = bean_from_wire(&wire, None, "f", 0);
            assert_eq!(back.mix, Some(mix));
        }
    }

    // ── roaster wire round-trip ────────────────────────────────────────

    #[test]
    fn roaster_round_trips_visualizer_fields() {
        let mut roaster = Roaster::new("roaster-1".to_owned(), "Onyx".to_owned(), 0);
        roaster.visualizer_id = Some("rv-7".to_owned());
        roaster.website = Some("https://onyx.coffee".to_owned());
        roaster.image_url = Some("https://onyx.coffee/logo.png".to_owned());
        roaster.canonical_roaster_id = Some("canon-3".to_owned());

        let back = roaster_from_wire(&roaster_to_wire(&roaster), "fallback", 0);
        assert_eq!(back.name, "Onyx");
        assert_eq!(back.visualizer_id.as_deref(), Some("rv-7"));
        assert_eq!(back.website.as_deref(), Some("https://onyx.coffee"));
        assert_eq!(
            back.image_url.as_deref(),
            Some("https://onyx.coffee/logo.png")
        );
        assert_eq!(back.canonical_roaster_id.as_deref(), Some("canon-3"));
    }

    // ── ISO datetime round-trip ────────────────────────────────────────

    #[test]
    fn iso_datetime_round_trips() {
        // 2026-05-01T12:34:56.789Z
        let ms = parse_iso_datetime_ms("2026-05-01T12:34:56.789Z").unwrap();
        assert_eq!(format_iso_datetime_ms(ms), "2026-05-01T12:34:56.789Z");
        // The epoch.
        assert_eq!(format_iso_datetime_ms(0), "1970-01-01T00:00:00.000Z");
        assert_eq!(parse_iso_datetime_ms("1970-01-01T00:00:00.000Z"), Some(0));
    }

    #[test]
    fn iso_datetime_parses_lenient_variants() {
        // No millis.
        assert_eq!(
            parse_iso_datetime_ms("2026-05-01T00:00:00Z"),
            Some(parse_iso_datetime_ms("2026-05-01T00:00:00.000Z").unwrap())
        );
        // Date only.
        assert_eq!(
            parse_iso_datetime_ms("2026-05-01"),
            Some(days_from_civil(2026, 5, 1).unwrap() * MS_PER_DAY)
        );
        // Positive offset shifts back to UTC.
        let z = parse_iso_datetime_ms("2026-05-01T01:00:00+01:00").unwrap();
        assert_eq!(z, parse_iso_datetime_ms("2026-05-01T00:00:00Z").unwrap());
        // Garbage → None.
        assert_eq!(parse_iso_datetime_ms("not-a-date"), None);
    }

    #[test]
    fn bean_archived_at_round_trips() {
        let mut bean = round_trip_bean();
        bean.archived_at = Some(1_700_000_123_456);
        let wire = bean_to_wire(&bean, None);
        let back = bean_from_wire(&wire, None, "f", 0);
        assert_eq!(back.archived_at, Some(1_700_000_123_456));
    }

    // ── wireShotFromDetail (Default + Beanconqueror variants) ──────────

    #[test]
    fn wire_shot_from_default_detail() {
        let detail = serde_json::json!({
            "profile_title": "best of decent",
            "duration": 30.5,                 // seconds
            "drink_weight": "36.2",           // stringified grams
            "private_notes": "tasty",
            "flavor": 12,                     // 0..15 → rating 4
            "tags": ["daily-driver", 7, "lever"]
        });
        let w = wire_shot_from_detail("r-1", 1_700_000_000, 1_700_000_050, &detail);
        assert_eq!(w.id, "r-1");
        assert_eq!(w.clock, 1_700_000_000_000);
        assert_eq!(w.updated_at_ms, Some(1_700_000_050_000));
        assert_eq!(w.duration_ms, 30_500);
        assert_eq!(w.profile_title.as_deref(), Some("best of decent"));
        assert_eq!(w.final_weight_g, Some(36.2));
        assert_eq!(w.notes.as_deref(), Some("tasty"));
        assert_eq!(w.rating, Some(4));
        assert_eq!(w.tag_list, vec!["daily-driver", "lever"]); // non-strings dropped
    }

    #[test]
    fn wire_shot_from_beanconqueror_detail_reads_nested_meta() {
        let detail = serde_json::json!({
            "profile_title": "bc shot",
            "meta": { "visualizer": {
                "duration": 27.0,
                "private_notes": "bc notes",
                "espresso_enjoyment": 9      // → rating 3
            }}
        });
        let w = wire_shot_from_detail("bc-1", 1_700_000_000, 1_700_000_000, &detail);
        assert_eq!(w.duration_ms, 27_000);
        assert_eq!(w.notes.as_deref(), Some("bc notes"));
        assert_eq!(w.rating, Some(3));
        assert_eq!(w.final_weight_g, None); // no drink_weight
        assert!(w.tag_list.is_empty());
    }

    #[test]
    fn wire_shot_missing_duration_is_zero() {
        let w = wire_shot_from_detail("x", 0, 0, &serde_json::json!({}));
        assert_eq!(w.duration_ms, 0);
        assert_eq!(w.profile_title, None);
        assert_eq!(w.rating, None);
    }

    // ── samplesFromVisualizerDetail ────────────────────────────────────

    #[test]
    fn samples_zip_columns_into_rows() {
        let detail = serde_json::json!({
            "timeframe": [0, 0.5, 1.0],
            "data": {
                "espresso_pressure": [0, 6.0, 9.0],
                "espresso_flow": [0, 1.5, 2.0],
                "espresso_temperature_basket": [90, 92, 93],
                "espresso_temperature_mix": [88, 89, 90],
                "espresso_temperature_goal": [93, 93, 93],
                "espresso_pressure_goal": [9, 9, 9],
                "espresso_flow_goal": [2, 2, 2],
                "espresso_weight": [0, 5.0, 18.0],
                "espresso_flow_weight": [0, 1.2, 1.8],
                "espresso_water_dispensed": [0, 6.0, 20.0]
            }
        });
        let samples = samples_from_visualizer_detail(&detail);
        assert_eq!(samples.len(), 3);

        // Time axis seconds → ms.
        assert_eq!(samples[1].elapsed, Duration::from_millis(500));
        assert_eq!(samples[2].elapsed, Duration::from_millis(1000));

        // Channel mapping for the second row.
        let s = &samples[1];
        assert_eq!(s.sample.group_pressure, 6.0);
        assert_eq!(s.sample.group_flow, 1.5);
        assert_eq!(s.sample.head_temp, 92.0); // from basket
        assert_eq!(s.sample.mix_temp, 89.0);
        assert_eq!(s.sample.set_head_temp, 93.0); // from temp goal
        assert_eq!(s.sample.set_group_pressure, 9.0);
        assert_eq!(s.sample.set_group_flow, 2.0);
        assert_eq!(s.sample.set_mix_temp, 0.0);
        assert_eq!(s.sample.frame_number, 0);

        // opt(): a positive reading is Some; the zeroth row's zeros are None.
        assert_eq!(s.scale_weight, Some(5.0));
        assert_eq!(s.scale_flow_weight, Some(1.2));
        assert_eq!(s.dispensed_volume, Some(6.0));
        assert_eq!(samples[0].scale_weight, None);
        assert_eq!(samples[0].dispensed_volume, None);
    }

    #[test]
    fn samples_empty_without_timeframe() {
        assert!(samples_from_visualizer_detail(&serde_json::json!({})).is_empty());
        assert!(samples_from_visualizer_detail(&serde_json::json!({ "timeframe": [] })).is_empty());
        // A Beanconqueror row (no curve) yields no samples.
        assert!(
            samples_from_visualizer_detail(&serde_json::json!({ "meta": { "visualizer": {} } }))
                .is_empty()
        );
    }

    #[test]
    fn samples_coerce_stringified_numbers_and_short_columns() {
        // A channel shorter than `timeframe` reads 0 past its end; string
        // numerics coerce like JS `Number(...)`.
        let detail = serde_json::json!({
            "timeframe": ["0", "1"],
            "data": { "espresso_pressure": ["6.5"] }
        });
        let samples = samples_from_visualizer_detail(&detail);
        assert_eq!(samples.len(), 2);
        assert_eq!(samples[0].sample.group_pressure, 6.5);
        assert_eq!(samples[1].sample.group_pressure, 0.0); // past the column end
        assert_eq!(samples[1].elapsed, Duration::from_millis(1000));
    }

    // ── JSON facades ───────────────────────────────────────────────────

    #[test]
    fn json_facades_round_trip() {
        let bean = round_trip_bean();
        let bean_json = serde_json::to_string(&bean).unwrap();
        let wire_json = bean_to_wire_json(&bean_json, Some("rv-1")).unwrap();
        let back_json = bean_from_wire_json(&wire_json, Some("r-local"), "f", 0).unwrap();
        let back: Bean = serde_json::from_str(&back_json).unwrap();
        assert_eq!(back.name, "Yirgacheffe");
        assert_eq!(back.mix, Some(BeanMix::Single));
        assert_eq!(back.roaster_id.as_deref(), Some("r-local"));
    }

    #[test]
    fn json_facades_surface_parse_errors() {
        assert!(bean_to_wire_json("not json", None).is_err());
        assert!(bean_from_wire_json("not json", None, "f", 0).is_err());
        assert!(wire_shot_from_detail_json("not json").is_err());
        assert!(samples_from_visualizer_detail_json("not json").is_err());
    }

    #[test]
    fn wire_shot_json_facade_parses_summary_and_detail() {
        let payload = serde_json::json!({
            "summary": { "id": "r-1", "clock": 1_700_000_000, "updated_at": 1_700_000_050 },
            "detail": { "profile_title": "p", "duration": 25.0, "drink_weight": "40" }
        })
        .to_string();
        let out = wire_shot_from_detail_json(&payload).unwrap();
        let w: WireShot = serde_json::from_str(&out).unwrap();
        assert_eq!(w.id, "r-1");
        assert_eq!(w.duration_ms, 25_000);
        assert_eq!(w.final_weight_g, Some(40.0));
    }
}

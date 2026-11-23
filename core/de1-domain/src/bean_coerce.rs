//! # bean-coerce
//!
//! Defensive deserialisers for stored [`Bean`] / [`Roaster`] rows, ported
//! from the web shell's `coerceBean` / `coerceRoaster` (`bean/model.ts`) so
//! every shell normalises untrusted localStorage / Room rows identically.
//!
//! These are NOT plain serde deserialisers: a stored row may carry a
//! wrong-typed field (a number where a string is expected, an invalid enum
//! string, a non-string in `tags`), and the contract is to **skip that field
//! and keep the rest** — never to reject the whole record — so a stale shape
//! can't crash a load. So each field is read defensively from a
//! [`serde_json::Value`], exactly mirroring the TS `typeof` checks. The only
//! hard requirement is a string `id` + `name`; absent either → `None`.
//!
//! Legacy aliases (`bagSizeG`→`bagSize`, `remainingG`→`remaining`) migrate on
//! read, matching the TS. `now_ms` seeds the `created_at` / `updated_at`
//! defaults (the shell passes `Date.now()`, as the TS `blankBean` does).

use crate::bag_grind::BagGrind;
use crate::bean::{Bean, BeanMix, BeanOrigin, BeanRoastType, Roaster};
use crate::grinder::Grinder;
use serde_json::{Map, Value};

/// Read `obj[key]` as an owned string, or `None` when absent / not a string.
fn str_field(obj: &Map<String, Value>, key: &str) -> Option<String> {
    obj.get(key).and_then(Value::as_str).map(str::to_owned)
}

/// Whether a value is an object or array (the TS `typeof x === 'object' &&
/// x !== null`, which `metadata` is stored under).
fn is_object_or_array(v: &Value) -> bool {
    v.is_object() || v.is_array()
}

/// Coerce a stored row into a valid [`Bean`], filling missing fields from
/// [`Bean::new`] and silently skipping wrong-typed ones. `None` only when the
/// row isn't an object or lacks a string `id` / `name`. Mirrors `coerceBean`.
#[allow(clippy::cast_possible_truncation)]
// Sound casts: `roastLevel` / `rating` are small integer scales → u8;
// `bagSize` / `remaining` / `cost` are grams / a price → f32 (the field
// types). A corrupt out-of-range float saturates rather than panicking.
#[must_use]
pub fn coerce_bean(raw: &Value, now_ms: i64) -> Option<Bean> {
    let obj = raw.as_object()?;
    let id = obj.get("id").and_then(Value::as_str)?;
    let name = obj.get("name").and_then(Value::as_str)?;
    let mut bean = Bean::new(id.to_owned(), name.to_owned(), now_ms);
    // The TS `blankBean` seeds `metadata` to `{}`, not `null`.
    bean.metadata = Value::Object(Map::new());

    if let Some(v) = str_field(obj, "roasterId") {
        bean.roaster_id = Some(v);
    }
    if let Some(v) = str_field(obj, "linkedProfileId") {
        bean.linked_profile_id = Some(v);
    }
    if let Some(v) = str_field(obj, "roastedOn") {
        bean.roasted_on = Some(v);
    }
    if let Some(v) = str_field(obj, "openedOn") {
        bean.opened_on = Some(v);
    }
    if let Some(v) = str_field(obj, "frozenOn") {
        bean.frozen_on = Some(v);
    }
    if let Some(v) = str_field(obj, "defrostedOn") {
        bean.defrosted_on = Some(v);
    }
    if let Some(n) = obj
        .get("roastLevel")
        .and_then(crate::coerce::as_f64)
        .filter(|n| n.is_finite())
    {
        bean.roast_level = Some(n as u8);
    }
    match obj.get("mix").and_then(Value::as_str) {
        Some("single") => bean.mix = Some(BeanMix::Single),
        Some("blend") => bean.mix = Some(BeanMix::Blend),
        _ => {}
    }
    match obj.get("roastType").and_then(Value::as_str) {
        Some("espresso") => bean.roast_type = Some(BeanRoastType::Espresso),
        Some("filter") => bean.roast_type = Some(BeanRoastType::Filter),
        Some("omni") => bean.roast_type = Some(BeanRoastType::Omni),
        _ => {}
    }
    if let Some(b) = obj.get("decaf").and_then(Value::as_bool) {
        bean.decaf = b;
    }
    if let Some(Value::Object(o)) = obj.get("origin") {
        bean.origin = BeanOrigin {
            country: str_field(o, "country"),
            region: str_field(o, "region"),
            farm: str_field(o, "farm"),
            farmer: str_field(o, "farmer"),
            variety: str_field(o, "variety"),
            elevation: str_field(o, "elevation"),
            processing: str_field(o, "processing"),
            harvest_time: str_field(o, "harvestTime"),
        };
    }
    // Accept the new name, else the legacy `*G` key (records pre-rename).
    if let Some(n) = obj.get("bagSize").and_then(crate::coerce::as_f64) {
        bean.bag_size = n as f32;
    } else if let Some(n) = obj.get("bagSizeG").and_then(crate::coerce::as_f64) {
        bean.bag_size = n as f32;
    }
    if let Some(n) = obj.get("remaining").and_then(crate::coerce::as_f64) {
        bean.remaining = n as f32;
    } else if let Some(n) = obj.get("remainingG").and_then(crate::coerce::as_f64) {
        bean.remaining = n as f32;
    }
    if let Some(v) = str_field(obj, "qualityScore") {
        bean.quality_score = v;
    }
    if let Some(v) = str_field(obj, "tastingNotes") {
        bean.tasting_notes = v;
    }
    if let Some(n) = obj
        .get("rating")
        .and_then(crate::coerce::as_f64)
        .filter(|n| n.is_finite())
    {
        bean.rating = n as u8;
    }
    if let Some(v) = str_field(obj, "placeOfPurchase") {
        bean.place_of_purchase = Some(v);
    }
    if let Some(v) = str_field(obj, "url") {
        bean.url = Some(v);
    }
    if let Some(v) = str_field(obj, "notes") {
        bean.notes = v;
    }
    if let Some(b) = obj.get("favourite").and_then(Value::as_bool) {
        bean.favourite = b;
    }
    if let Some(n) = obj.get("archivedAt").and_then(Value::as_i64) {
        bean.archived_at = Some(n);
    }
    if let Some(n) = obj.get("deletedAt").and_then(Value::as_i64) {
        bean.deleted_at = Some(n);
    }
    if let Some(v) = str_field(obj, "grinder") {
        bean.grinder = v;
    }
    if let Some(v) = str_field(obj, "grinderSetting") {
        bean.grinder_setting = v;
    }
    if let Some(Value::Array(rows)) = obj.get("grinds") {
        bean.grinds = Some(rows.iter().filter_map(coerce_bag_grind).collect());
    }
    // The old grind still waiting to be folded in (save-boundaries follow-up
    // 60): only `true` means anything.
    if obj.get("legacyGrindPending").and_then(Value::as_bool) == Some(true) {
        bean.legacy_grind_pending = Some(true);
    }
    if let Some(Value::Array(arr)) = obj.get("tags") {
        bean.tags = arr
            .iter()
            .filter_map(|t| t.as_str().map(str::to_owned))
            .collect();
    }
    if let Some(v) = str_field(obj, "visualizerId") {
        bean.visualizer_id = Some(v);
    }
    if let Some(v) = str_field(obj, "canonicalCoffeeBagId") {
        bean.canonical_coffee_bag_id = Some(v);
    }
    if let Some(v) = str_field(obj, "canonicalRoasterId") {
        bean.canonical_roaster_id = Some(v);
    }
    if let Some(v) = str_field(obj, "beanconquerorId") {
        bean.beanconqueror_id = Some(v);
    }
    if let Some(v) = str_field(obj, "imageRef") {
        bean.image_ref = Some(v);
    }
    if let Some(n) = obj
        .get("cost")
        .and_then(crate::coerce::as_f64)
        .filter(|n| n.is_finite() && *n >= 0.0)
    {
        bean.cost = Some(n as f32);
    }
    if let Some(m) = obj.get("metadata").filter(|m| is_object_or_array(m)) {
        bean.metadata = m.clone();
    }
    if let Some(n) = obj.get("createdAt").and_then(Value::as_i64) {
        bean.created_at = n;
    }
    if let Some(n) = obj.get("updatedAt").and_then(Value::as_i64) {
        bean.updated_at = n;
    }
    Some(bean)
}

/// One saved grind (#488), read leniently: a wrong-typed field takes its
/// default, a numeric grind reads as its text; a non-object is skipped.
/// Rows are never de-duplicated here: an unknown grinder's row is kept.
fn coerce_bag_grind(raw: &Value) -> Option<BagGrind> {
    let obj = raw.as_object()?;
    let grind = match obj.get("grind") {
        Some(Value::String(s)) => s.clone(),
        Some(Value::Number(n)) => n.to_string(),
        _ => String::new(),
    };
    Some(BagGrind {
        grinder_id: str_field(obj, "grinderId").unwrap_or_default(),
        grinder_name: str_field(obj, "grinderName").unwrap_or_default(),
        grind,
        starred: obj.get("starred").and_then(Value::as_bool).unwrap_or(false),
    })
}

/// Coerce a stored grinder row (#488) into a valid [`Grinder`]: each field
/// with the right type is kept (the scale field by field), the rest take
/// their defaults. `None` only when the row isn't an object or lacks a
/// non-empty string `id`.
#[must_use]
pub fn coerce_grinder(raw: &Value) -> Option<Grinder> {
    let obj = raw.as_object()?;
    let id = obj
        .get("id")
        .and_then(Value::as_str)
        .filter(|id| !id.is_empty())?;
    let mut g = Grinder {
        id: id.to_owned(),
        ..Grinder::default()
    };
    if let Some(v) = str_field(obj, "name") {
        g.name = v;
    }
    if let Some(scale) = obj.get("scale").and_then(Value::as_object) {
        let num = |k: &str| scale.get(k).and_then(crate::coerce::as_f64);
        #[allow(clippy::cast_possible_truncation)]
        let as_f32 = |n: f64| n as f32;
        if let Some(n) = num("min") {
            g.scale.min = as_f32(n);
        }
        if let Some(n) = num("max") {
            g.scale.max = as_f32(n);
        }
        if let Some(n) = num("step") {
            g.scale.step = as_f32(n);
        }
        if let Some(v) = str_field(scale, "unit") {
            g.scale.unit = v;
        }
    }
    g.preset_id = str_field(obj, "presetId");
    if let Some(n) = obj.get("updatedAt").and_then(Value::as_i64) {
        g.updated_at = n;
    }
    g.archived_at = obj.get("archivedAt").and_then(Value::as_i64);
    g.deleted_at = obj.get("deletedAt").and_then(Value::as_i64);
    // Cleaned, and never empty: one with no usable name takes its catalogue
    // entry's, else "Grinder" (#488 closing check, PLAN 64).
    g.name = crate::grinder::imported_grinder_name(&g);
    Some(g)
}

/// JSON-bridged [`coerce_grinder`]: a stored row in, a `Grinder` JSON or
/// `None` out.
#[must_use]
pub fn coerce_grinder_json(raw_json: &str) -> Option<String> {
    let raw: Value = serde_json::from_str(raw_json).ok()?;
    serde_json::to_string(&coerce_grinder(&raw)?).ok()
}

/// Coerce a stored row into a valid [`Roaster`]; `None` on garbage / a row
/// missing a string `id` / `name`. Mirrors `coerceRoaster`.
#[must_use]
pub fn coerce_roaster(raw: &Value, now_ms: i64) -> Option<Roaster> {
    let obj = raw.as_object()?;
    let id = obj.get("id").and_then(Value::as_str)?;
    let name = obj.get("name").and_then(Value::as_str)?;
    let mut roaster = Roaster::new(id.to_owned(), name.to_owned(), now_ms);
    roaster.metadata = Value::Object(Map::new()); // `blankRoaster` seeds `{}`.

    if let Some(v) = str_field(obj, "website") {
        roaster.website = Some(v);
    }
    if let Some(v) = str_field(obj, "imageUrl") {
        roaster.image_url = Some(v);
    }
    if let Some(v) = str_field(obj, "city") {
        roaster.city = Some(v);
    }
    if let Some(v) = str_field(obj, "country") {
        roaster.country = Some(v);
    }
    if let Some(v) = str_field(obj, "notes") {
        roaster.notes = v;
    }
    if let Some(v) = str_field(obj, "canonicalRoasterId") {
        roaster.canonical_roaster_id = Some(v);
    }
    if let Some(v) = str_field(obj, "catalogueRoasterId") {
        roaster.catalogue_roaster_id = Some(v);
    }
    if let Some(v) = str_field(obj, "visualizerId") {
        roaster.visualizer_id = Some(v);
    }
    if let Some(n) = obj.get("deletedAt").and_then(Value::as_i64) {
        roaster.deleted_at = Some(n);
    }
    if let Some(m) = obj.get("metadata").filter(|m| is_object_or_array(m)) {
        roaster.metadata = m.clone();
    }
    if let Some(n) = obj.get("createdAt").and_then(Value::as_i64) {
        roaster.created_at = n;
    }
    if let Some(n) = obj.get("updatedAt").and_then(Value::as_i64) {
        roaster.updated_at = n;
    }
    Some(roaster)
}

/// JSON-bridged [`coerce_bean`]. Parses `raw_json` to a [`Value`] and coerces
/// it. `None` (→ JS `null`) when `raw_json` is invalid JSON, isn't an object,
/// or lacks `id` / `name`. Never errors — the boundary stays panic-free.
#[must_use]
pub fn coerce_bean_json(raw_json: &str, now_ms: i64) -> Option<String> {
    let raw: Value = serde_json::from_str(raw_json).ok()?;
    let bean = coerce_bean(&raw, now_ms)?;
    serde_json::to_string(&bean).ok()
}

/// JSON-bridged [`coerce_roaster`]. See [`coerce_bean_json`].
#[must_use]
pub fn coerce_roaster_json(raw_json: &str, now_ms: i64) -> Option<String> {
    let raw: Value = serde_json::from_str(raw_json).ok()?;
    let roaster = coerce_roaster(&raw, now_ms)?;
    serde_json::to_string(&roaster).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    // ── #488: saved grinds and grinder rows ──

    #[test]
    fn a_stored_bags_grinds_survive_the_coerce_leniently() {
        let raw = serde_json::json!({
            "id": "b1", "name": "Guji",
            "grinds": [
                { "grinderId": "grinder:nz", "grinderName": "Niche Zero", "grind": "14", "starred": true },
                { "grinderId": "grinder:gone", "grind": 480, "starred": "yes" },
                "not a row",
            ],
        });
        let b = coerce_bean(&raw, 0).unwrap();
        let rows = b.grinds.unwrap();
        assert_eq!(rows.len(), 2);
        assert_eq!((rows[0].grind.as_str(), rows[0].starred), ("14", true));
        assert_eq!(
            (
                rows[1].grinder_id.as_str(),
                rows[1].grind.as_str(),
                rows[1].starred
            ),
            ("grinder:gone", "480", false)
        );
        // No `grinds`: not migrated yet.
        let old = coerce_bean(
            &serde_json::json!({ "id": "b1", "name": "x", "grinderSetting": "3" }),
            0,
        )
        .unwrap();
        assert_eq!((old.grinds, old.grinder_setting.as_str()), (None, "3"));
    }

    /// Save-boundaries follow-up 60 (REVIEW-540 M1): the waiting mark survives.
    #[test]
    fn a_waiting_legacy_grind_mark_survives_the_coerce() {
        let row = |pending: serde_json::Value| {
            coerce_bean(
                &serde_json::json!({
                    "id": "b1", "name": "x", "grinderSetting": "14",
                    "grinds": [{ "grinderId": "grinder:ek", "grind": "9" }],
                    "legacyGrindPending": pending,
                }),
                0,
            )
            .unwrap()
            .legacy_grind_pending
        };
        assert_eq!(row(serde_json::json!(true)), Some(true));
        assert_eq!(row(serde_json::json!(false)), None);
        assert_eq!(row(serde_json::json!("yes")), None);
        assert_eq!(row(serde_json::Value::Null), None);
    }

    #[test]
    fn a_grinder_row_reads_field_by_field() {
        let g = coerce_grinder(&serde_json::json!({
            "id": "grinder:a", "name": 7, "scale": { "min": "low", "max": 40, "step": 1, "unit": "clicks" },
            "presetId": "niche-zero", "updatedAt": 5, "archivedAt": 6,
        }))
        .unwrap();
        // No usable name: its catalogue entry's (PLAN 64).
        let niche = crate::grinder_catalogue::catalogue_entry("niche-zero")
            .unwrap()
            .name();
        assert_eq!(
            (g.id.as_str(), g.name.as_str()),
            ("grinder:a", niche.as_str())
        );
        assert_eq!(
            (
                g.scale.min,
                g.scale.max,
                g.scale.step,
                g.scale.unit.as_str()
            ),
            (0.0, 40.0, 1.0, "clicks")
        );
        assert_eq!(
            (g.preset_id.as_deref(), g.updated_at, g.archived_at),
            (Some("niche-zero"), 5, Some(6))
        );
        assert!(coerce_grinder(&serde_json::json!({ "name": "x" })).is_none());
        assert!(coerce_grinder_json("nope").is_none());
    }

    /// PLAN 64: a stored name that cleans to nothing takes the catalogue
    /// entry's name, else "Grinder"; never an empty grinder name.
    #[test]
    fn a_grinder_row_whose_name_cleans_to_nothing_gets_a_name() {
        let named = |raw: serde_json::Value| coerce_grinder(&raw).unwrap().name;
        let niche = crate::grinder_catalogue::catalogue_entry("niche-zero")
            .unwrap()
            .name();
        assert_eq!(
            named(
                serde_json::json!({ "id": "g:1", "name": "\n\t\u{7}", "presetId": "niche-zero" })
            ),
            niche
        );
        assert_eq!(
            named(serde_json::json!({ "id": "g:2", "name": "  " })),
            "Grinder"
        );
        assert_eq!(
            named(serde_json::json!({ "id": "g:3", "presetId": "no-such-entry" })),
            "Grinder"
        );
        assert_eq!(
            named(serde_json::json!({ "id": "g:4", "name": "EK43\n" })),
            "EK43",
            "a usable name is only cleaned"
        );
    }

    // ── coerce_bean (mirrors model.vitest.ts) ──────────────────────────

    #[test]
    fn rejects_non_objects_and_rows_missing_id_or_name() {
        assert!(coerce_bean(&Value::Null, 0).is_none());
        assert!(coerce_bean(&serde_json::json!(42), 0).is_none());
        assert!(coerce_bean(&serde_json::json!({ "name": "x" }), 0).is_none()); // no id
        assert!(coerce_bean(&serde_json::json!({ "id": "b1" }), 0).is_none()); // no name
    }

    #[test]
    fn fills_defaults_for_a_minimal_valid_row() {
        let bean = coerce_bean(&serde_json::json!({ "id": "b1", "name": "House" }), 99).unwrap();
        assert_eq!(bean.id, "b1");
        assert_eq!(bean.name, "House");
        assert_eq!(bean.bag_size, 0.0);
        assert_eq!(bean.remaining, 0.0);
        assert!(bean.tags.is_empty());
        assert_eq!(bean.mix, None);
        assert_eq!(bean.metadata, serde_json::json!({})); // `{}`, not null
        assert_eq!(bean.created_at, 99);
        assert_eq!(bean.updated_at, 99);
    }

    #[test]
    fn migrates_legacy_bag_size_g_and_remaining_g() {
        let bean = coerce_bean(
            &serde_json::json!({ "id": "b1", "name": "House", "bagSizeG": 340, "remainingG": 200 }),
            0,
        )
        .unwrap();
        assert_eq!(bean.bag_size, 340.0);
        assert_eq!(bean.remaining, 200.0);
    }

    #[test]
    fn drops_non_string_tags_and_ignores_bad_enum_and_cost() {
        let bean = coerce_bean(
            &serde_json::json!({
                "id": "b1", "name": "House",
                "tags": ["a", 7, null, "b"], "mix": "nonsense", "cost": -5
            }),
            0,
        )
        .unwrap();
        assert_eq!(bean.tags, vec!["a", "b"]);
        assert_eq!(bean.mix, None);
        assert_eq!(bean.cost, None);
    }

    #[test]
    fn coerces_stringified_numeric_fields() {
        // A hand-edited or older record may carry numbers as strings. Via the
        // shared `coerce::as_f64`, they coerce rather than silently dropping to
        // defaults (the strict `Value::as_f64` used to drop them).
        let bean = coerce_bean(
            &serde_json::json!({
                "id": "b1", "name": "House",
                "roastLevel": "5", "bagSize": "340", "remaining": "200", "rating": "4"
            }),
            0,
        )
        .unwrap();
        assert_eq!(bean.roast_level, Some(5));
        assert_eq!(bean.bag_size, 340.0);
        assert_eq!(bean.remaining, 200.0);
        assert_eq!(bean.rating, 4);
    }

    #[test]
    fn reads_a_nested_origin_defensively() {
        let bean = coerce_bean(
            &serde_json::json!({
                "id": "b1", "name": "House",
                "origin": { "country": "Kenya", "region": 12, "farm": "Gatomboya" }
            }),
            0,
        )
        .unwrap();
        assert_eq!(bean.origin.country.as_deref(), Some("Kenya"));
        assert_eq!(bean.origin.farm.as_deref(), Some("Gatomboya"));
        assert_eq!(bean.origin.region, None); // non-string dropped
    }

    #[test]
    fn accepts_a_valid_cost_and_enum() {
        let bean = coerce_bean(
            &serde_json::json!({ "id": "b1", "name": "House", "mix": "blend", "cost": 18.5 }),
            0,
        )
        .unwrap();
        assert_eq!(bean.mix, Some(BeanMix::Blend));
        assert_eq!(bean.cost, Some(18.5));
    }

    // ── coerce_roaster ─────────────────────────────────────────────────

    #[test]
    fn roaster_rejects_garbage_and_rows_missing_id_or_name() {
        assert!(coerce_roaster(&Value::Null, 0).is_none());
        assert!(coerce_roaster(&serde_json::json!({ "name": "x" }), 0).is_none());
        assert!(coerce_roaster(&serde_json::json!({ "id": "r1" }), 0).is_none());
    }

    #[test]
    fn roaster_reads_modelled_fields_and_fills_defaults() {
        let roaster = coerce_roaster(
            &serde_json::json!({
                "id": "r1", "name": "Onyx",
                "website": "https://onyx.coffee", "visualizerId": "rv-1",
                "notes": 7  // non-string ignored
            }),
            0,
        )
        .unwrap();
        assert_eq!(roaster.id, "r1");
        assert_eq!(roaster.name, "Onyx");
        assert_eq!(roaster.website.as_deref(), Some("https://onyx.coffee"));
        assert_eq!(roaster.visualizer_id.as_deref(), Some("rv-1"));
        assert_eq!(roaster.notes, "");
        assert_eq!(roaster.metadata, serde_json::json!({}));
    }

    #[test]
    fn reads_catalogue_links_and_skips_wrong_types() {
        let bean = coerce_bean(
            &serde_json::json!({
                "id": "b1", "name": "House",
                "canonicalCoffeeBagId": "cb-1", "canonicalRoasterId": 42
            }),
            0,
        )
        .unwrap();
        assert_eq!(bean.canonical_coffee_bag_id.as_deref(), Some("cb-1"));
        assert_eq!(bean.canonical_roaster_id, None);
        let roaster = coerce_roaster(
            &serde_json::json!({ "id": "r1", "name": "Onyx", "catalogueRoasterId": "cr-1" }),
            0,
        )
        .unwrap();
        assert_eq!(roaster.catalogue_roaster_id.as_deref(), Some("cr-1"));
        assert_eq!(roaster.canonical_roaster_id, None);
    }

    // ── JSON facades ───────────────────────────────────────────────────

    #[test]
    fn json_facade_returns_none_on_garbage() {
        assert!(coerce_bean_json("not json", 0).is_none());
        assert!(coerce_bean_json("42", 0).is_none());
        assert!(coerce_bean_json(r#"{"name":"x"}"#, 0).is_none());
        assert!(coerce_roaster_json("not json", 0).is_none());
    }

    #[test]
    fn json_facade_round_trips_a_valid_row() {
        let out = coerce_bean_json(r#"{"id":"b1","name":"House","bagSizeG":250}"#, 0).unwrap();
        let bean: Bean = serde_json::from_str(&out).unwrap();
        assert_eq!(bean.id, "b1");
        assert_eq!(bean.bag_size, 250.0);
    }
}

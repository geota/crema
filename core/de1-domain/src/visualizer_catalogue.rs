//! # visualizer-catalogue
//!
//! The Visualizer **canonical catalogue** — the shared, community-maintained
//! list of roasters and coffee bags behind `GET /api/canonical_coffee_bags`
//! (open to every account, free or Premium). Crema's bean form searches it so
//! a user can pick a bag and have the form filled in.
//!
//! Sans-IO, like the rest of the core: the shells do the (debounced) HTTP and
//! hand the raw response body here.
//!
//! - [`parse_catalogue_coffee_bags`] reads a `CanonicalCoffeeBagListResponse`
//!   defensively into a [`CataloguePage`] of [`CatalogueCoffeeBag`] rows
//!   (blank strings collapse to `None`; a malformed row is skipped, never
//!   failing the page), each with a ready-to-render `meta` line.
//! - [`catalogue_autofill`] applies a picked row onto a [`Bean`] under the
//!   no-clobber rule: by default only **empty** fields are filled; with
//!   `replace_all` every field the catalogue has a value for is overwritten
//!   (a catalogue blank never erases what the user typed). The catalogue links
//!   (`canonical_coffee_bag_id` / `canonical_roaster_id`) are always set —
//!   the pick *is* the link.

use crate::bean::Bean;
use crate::visualizer_wire::roast_level_from_wire;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use typeshare::typeshare;

/// One `CanonicalCoffeeBagSummary` row, normalised for the shells: trimmed,
/// blank → `None`, camelCase. Produced by [`parse_catalogue_coffee_bags`] and
/// consumed by [`catalogue_autofill`].
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CatalogueCoffeeBag {
    /// Catalogue coffee-bag id (`canonical_coffee_bag_id` on a user bag).
    pub id: String,
    /// Catalogue roaster id (`canonical_roaster_id` on a user roaster).
    pub canonical_roaster_id: String,
    /// The catalogue roaster's name.
    pub roaster_name: String,
    /// Bag name.
    pub name: String,
    /// Roaster product page.
    #[serde(default)]
    pub url: Option<String>,
    /// Free-text roast level (`"Light"`, `"Medium-Dark"`, …).
    #[serde(default)]
    pub roast_level: Option<String>,
    /// Country of origin.
    #[serde(default)]
    pub country: Option<String>,
    /// Region within the country.
    #[serde(default)]
    pub region: Option<String>,
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
    /// Tasting notes, free text.
    #[serde(default)]
    pub tasting_notes: Option<String>,
    /// Secondary line for a result row — country and process joined by
    /// `" · "` (`"Ethiopia · Washed"`), or `""` when neither is known.
    #[serde(default)]
    pub meta: String,
}

/// One page of catalogue search results.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CataloguePage {
    /// The rows, in server order. Malformed rows are dropped.
    pub entries: Vec<CatalogueCoffeeBag>,
    /// Total matches across all pages (`paging.count`; `0` when absent).
    pub count: u32,
    /// 1-based page number (`paging.page`; `1` when absent).
    pub page: u32,
    /// Total page count (`paging.pages`; `1` when absent).
    pub pages: u32,
}

/// The result of [`catalogue_autofill`].
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CatalogueAutofill {
    /// The bean with the picked catalogue entry applied.
    pub bean: Bean,
    /// The catalogue roaster's name when the shell should put it in the
    /// roaster field (resolving / creating the local roaster row is the
    /// shell's job, as for a typed name); `None` = leave the roaster alone.
    pub roaster_name: Option<String>,
    /// camelCase names of the fields that changed (`"name"`, `"roaster"`,
    /// `"origin.country"`, `"roastLevel"`, `"tastingNotes"`, …) — for a
    /// "filled N fields" hint. Excludes the catalogue-link ids.
    pub filled: Vec<String>,
}

/// Trimmed string or `None` for absent / non-string / blank.
fn clean(v: Option<&Value>) -> Option<String> {
    let t = v?.as_str()?.trim();
    (!t.is_empty()).then(|| t.to_owned())
}

/// Read one `CanonicalCoffeeBagSummary` object. `None` when the row lacks a
/// string `id` or a non-blank `name` (required by the spec).
fn parse_row(v: &Value) -> Option<CatalogueCoffeeBag> {
    let obj = v.as_object()?;
    let id = clean(obj.get("id"))?;
    let name = clean(obj.get("name"))?;
    let f = |k: &str| clean(obj.get(k));
    let country = f("country");
    let processing = f("processing");
    let meta = [country.as_deref(), processing.as_deref()]
        .into_iter()
        .flatten()
        .collect::<Vec<_>>()
        .join(" · ");
    Some(CatalogueCoffeeBag {
        id,
        canonical_roaster_id: f("canonical_roaster_id").unwrap_or_default(),
        roaster_name: f("canonical_roaster_name").unwrap_or_default(),
        name,
        url: f("url"),
        roast_level: f("roast_level"),
        country,
        region: f("region"),
        farmer: f("farmer"),
        variety: f("variety"),
        elevation: f("elevation"),
        processing,
        harvest_time: f("harvest_time"),
        tasting_notes: f("tasting_notes"),
        meta,
    })
}

/// Parse a `GET /canonical_coffee_bags` body (`CanonicalCoffeeBagListResponse`)
/// into a [`CataloguePage`]. Never fails: a non-object body is an empty page,
/// a malformed row is skipped.
#[must_use]
pub fn parse_catalogue_coffee_bags(body: &Value) -> CataloguePage {
    let entries = body
        .get("data")
        .and_then(Value::as_array)
        .map(|rows| rows.iter().filter_map(parse_row).collect())
        .unwrap_or_default();
    let paging = body.get("paging");
    let num = |k: &str, dflt: u32| {
        paging
            .and_then(|p| p.get(k))
            .and_then(Value::as_u64)
            .and_then(|n| u32::try_from(n).ok())
            .unwrap_or(dflt)
    };
    CataloguePage {
        entries,
        count: num("count", 0),
        page: num("page", 1).max(1),
        pages: num("pages", 1).max(1),
    }
}

/// Apply a picked catalogue row onto `bean`.
///
/// Fields mapped: name, roaster (via [`CatalogueAutofill::roaster_name`]),
/// origin country / region / farmer / variety / elevation / processing /
/// harvest time, roast level (Visualizer's band → Crema's 1..10 via
/// [`roast_level_from_wire`]), tasting notes and the buy-again URL.
///
/// - **Default (fill-empty)**: a field is written only when the bean's value
///   is empty (`None`, blank string, or an unset roast level). The roaster is
///   filled only when `roaster_set` is false — the shell knows whether its
///   roaster input holds a value (the draft's `roaster_id` may lag the input).
/// - **`replace_all`**: every field the catalogue has a value for overwrites
///   the bean's; catalogue blanks still leave the bean's value alone.
///
/// The catalogue links (`canonical_coffee_bag_id`, `canonical_roaster_id`)
/// are always set to the picked row's ids. `updated_at` is untouched (the
/// shell's store owns the bump).
#[must_use]
pub fn catalogue_autofill(
    bean: &Bean,
    entry: &CatalogueCoffeeBag,
    roaster_set: bool,
    replace_all: bool,
) -> CatalogueAutofill {
    let mut out = bean.clone();
    let mut filled: Vec<String> = Vec::new();

    // Optional-string fields.
    fn put_opt(
        slot: &mut Option<String>,
        value: Option<&String>,
        replace_all: bool,
        label: &str,
        filled: &mut Vec<String>,
    ) {
        let Some(v) = value else { return };
        let empty = slot.as_deref().is_none_or(|s| s.trim().is_empty());
        if (empty || replace_all) && slot.as_deref() != Some(v.as_str()) {
            *slot = Some(v.clone());
            filled.push(label.to_owned());
        }
    }
    // Plain-string fields (`""` = empty).
    fn put_str(
        slot: &mut String,
        value: Option<&String>,
        replace_all: bool,
        label: &str,
        filled: &mut Vec<String>,
    ) {
        let Some(v) = value else { return };
        if (slot.trim().is_empty() || replace_all) && slot != v {
            slot.clone_from(v);
            filled.push(label.to_owned());
        }
    }

    let name = Some(&entry.name).filter(|n| !n.trim().is_empty());
    put_str(&mut out.name, name, replace_all, "name", &mut filled);

    let roaster_name = Some(entry.roaster_name.trim())
        .filter(|n| !n.is_empty() && (!roaster_set || replace_all))
        .map(str::to_owned);
    if roaster_name.is_some() {
        filled.push("roaster".to_owned());
    }

    let o = &mut out.origin;
    put_opt(
        &mut o.country,
        entry.country.as_ref(),
        replace_all,
        "origin.country",
        &mut filled,
    );
    put_opt(
        &mut o.region,
        entry.region.as_ref(),
        replace_all,
        "origin.region",
        &mut filled,
    );
    put_opt(
        &mut o.farmer,
        entry.farmer.as_ref(),
        replace_all,
        "origin.farmer",
        &mut filled,
    );
    put_opt(
        &mut o.variety,
        entry.variety.as_ref(),
        replace_all,
        "origin.variety",
        &mut filled,
    );
    put_opt(
        &mut o.elevation,
        entry.elevation.as_ref(),
        replace_all,
        "origin.elevation",
        &mut filled,
    );
    put_opt(
        &mut o.processing,
        entry.processing.as_ref(),
        replace_all,
        "origin.processing",
        &mut filled,
    );
    put_opt(
        &mut o.harvest_time,
        entry.harvest_time.as_ref(),
        replace_all,
        "origin.harvestTime",
        &mut filled,
    );

    if let Some(level) = roast_level_from_wire(entry.roast_level.as_deref())
        && (out.roast_level.is_none() || replace_all)
        && out.roast_level != Some(level)
    {
        out.roast_level = Some(level);
        filled.push("roastLevel".to_owned());
    }

    put_str(
        &mut out.tasting_notes,
        entry.tasting_notes.as_ref(),
        replace_all,
        "tastingNotes",
        &mut filled,
    );
    put_opt(
        &mut out.url,
        entry.url.as_ref(),
        replace_all,
        "url",
        &mut filled,
    );

    out.canonical_coffee_bag_id = Some(entry.id.clone());
    out.canonical_roaster_id = Some(entry.canonical_roaster_id.clone()).filter(|id| !id.is_empty());

    CatalogueAutofill {
        bean: out,
        roaster_name,
        filled,
    }
}

/// JSON-bridged [`parse_catalogue_coffee_bags`]. Input: the raw response
/// body. Output: a [`CataloguePage`] JSON.
///
/// # Errors
/// The JSON parse / serialise error string when `body_json` isn't JSON.
pub fn parse_catalogue_coffee_bags_json(body_json: &str) -> Result<String, String> {
    let body: Value = serde_json::from_str(body_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&parse_catalogue_coffee_bags(&body)).map_err(|e| e.to_string())
}

/// JSON-bridged [`catalogue_autofill`]. Inputs: a [`Bean`] JSON and a
/// [`CatalogueCoffeeBag`] JSON. Output: a [`CatalogueAutofill`] JSON.
///
/// # Errors
/// The JSON parse / serialise error string on a malformed input.
pub fn catalogue_autofill_json(
    bean_json: &str,
    entry_json: &str,
    roaster_set: bool,
    replace_all: bool,
) -> Result<String, String> {
    let bean: Bean = serde_json::from_str(bean_json).map_err(|e| e.to_string())?;
    let entry: CatalogueCoffeeBag = serde_json::from_str(entry_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&catalogue_autofill(&bean, &entry, roaster_set, replace_all))
        .map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn body() -> Value {
        json!({
            "data": [
                {
                    "id": "cb-1",
                    "canonical_roaster_id": "cr-1",
                    "canonical_roaster_name": "Onyx Coffee Lab",
                    "name": "Ethiopia Guji Hambela",
                    "url": "https://onyxcoffeelab.com/hambela",
                    "roast_level": "Light",
                    "country": "Ethiopia",
                    "region": "Guji",
                    "farmer": null,
                    "variety": "Heirloom",
                    "elevation": "2000-2200 masl",
                    "processing": " Washed ",
                    "harvest_time": "",
                    "tasting_notes": "Bergamot, peach",
                    "created_at": "2026-01-01T00:00:00Z",
                    "updated_at": "2026-01-01T00:00:00Z"
                },
                { "id": "cb-bad", "name": "   " },
                { "name": "no id" },
                {
                    "id": "cb-2",
                    "canonical_roaster_id": "cr-2",
                    "canonical_roaster_name": "Sey",
                    "name": "Blend",
                    "created_at": "2026-01-01T00:00:00Z",
                    "updated_at": "2026-01-01T00:00:00Z"
                }
            ],
            "paging": { "count": 2, "page": 1, "limit": 10, "pages": 1 }
        })
    }

    fn entry() -> CatalogueCoffeeBag {
        parse_catalogue_coffee_bags(&body()).entries[0].clone()
    }

    #[test]
    fn parses_rows_trims_blanks_and_skips_malformed() {
        let page = parse_catalogue_coffee_bags(&body());
        assert_eq!(page.entries.len(), 2);
        assert_eq!((page.count, page.page, page.pages), (2, 1, 1));
        let e = &page.entries[0];
        assert_eq!(e.roaster_name, "Onyx Coffee Lab");
        assert_eq!(e.canonical_roaster_id, "cr-1");
        assert_eq!(e.processing.as_deref(), Some("Washed"));
        assert_eq!(e.farmer, None);
        assert_eq!(e.harvest_time, None);
        assert_eq!(e.meta, "Ethiopia · Washed");
        assert_eq!(page.entries[1].meta, "");
    }

    #[test]
    fn garbage_body_is_an_empty_page() {
        let page = parse_catalogue_coffee_bags(&json!("nope"));
        assert!(page.entries.is_empty());
        assert_eq!((page.count, page.page, page.pages), (0, 1, 1));
        assert!(parse_catalogue_coffee_bags_json("not json").is_err());
    }

    #[test]
    fn fills_every_empty_field_and_links_ids() {
        let bean = Bean::new("bean:1".into(), String::new(), 5);
        let r = catalogue_autofill(&bean, &entry(), false, false);
        let b = &r.bean;
        assert_eq!(b.name, "Ethiopia Guji Hambela");
        assert_eq!(r.roaster_name.as_deref(), Some("Onyx Coffee Lab"));
        assert_eq!(b.origin.country.as_deref(), Some("Ethiopia"));
        assert_eq!(b.origin.region.as_deref(), Some("Guji"));
        assert_eq!(b.origin.variety.as_deref(), Some("Heirloom"));
        assert_eq!(b.origin.elevation.as_deref(), Some("2000-2200 masl"));
        assert_eq!(b.origin.processing.as_deref(), Some("Washed"));
        assert_eq!(b.origin.farmer, None);
        assert_eq!(b.roast_level, Some(2));
        assert_eq!(b.tasting_notes, "Bergamot, peach");
        assert_eq!(b.url.as_deref(), Some("https://onyxcoffeelab.com/hambela"));
        assert_eq!(b.canonical_coffee_bag_id.as_deref(), Some("cb-1"));
        assert_eq!(b.canonical_roaster_id.as_deref(), Some("cr-1"));
        assert_eq!(b.updated_at, 5);
        assert_eq!(&r.filled[..2], ["name", "roaster"]);
        assert!(r.filled.contains(&"roastLevel".to_owned()));
        assert!(!r.filled.iter().any(|f| f.contains("canonical")));
    }

    #[test]
    fn never_clobbers_typed_fields_by_default() {
        let mut bean = Bean::new("bean:1".into(), "My name".into(), 0);
        bean.origin.country = Some("Kenya".into());
        bean.origin.region = Some("   ".into()); // blank counts as empty
        bean.roast_level = Some(8);
        bean.tasting_notes = "jammy".into();
        let r = catalogue_autofill(&bean, &entry(), true, false);
        assert_eq!(r.bean.name, "My name");
        assert_eq!(r.roaster_name, None);
        assert_eq!(r.bean.origin.country.as_deref(), Some("Kenya"));
        assert_eq!(r.bean.origin.region.as_deref(), Some("Guji"));
        assert_eq!(r.bean.roast_level, Some(8));
        assert_eq!(r.bean.tasting_notes, "jammy");
        assert!(!r.filled.contains(&"name".to_owned()));
        assert!(r.filled.contains(&"origin.region".to_owned()));
        // Links are still set — the pick is the link.
        assert_eq!(r.bean.canonical_coffee_bag_id.as_deref(), Some("cb-1"));
    }

    #[test]
    fn replace_all_overwrites_but_catalogue_blanks_keep_user_values() {
        let mut bean = Bean::new("bean:1".into(), "My name".into(), 0);
        bean.origin.country = Some("Kenya".into());
        bean.origin.farmer = Some("Tarekech".into());
        bean.origin.harvest_time = Some("2025".into());
        bean.roast_level = Some(8);
        let r = catalogue_autofill(&bean, &entry(), true, true);
        assert_eq!(r.bean.name, "Ethiopia Guji Hambela");
        assert_eq!(r.roaster_name.as_deref(), Some("Onyx Coffee Lab"));
        assert_eq!(r.bean.origin.country.as_deref(), Some("Ethiopia"));
        assert_eq!(r.bean.roast_level, Some(2));
        // Catalogue has no farmer / harvest time → user values survive.
        assert_eq!(r.bean.origin.farmer.as_deref(), Some("Tarekech"));
        assert_eq!(r.bean.origin.harvest_time.as_deref(), Some("2025"));
    }

    #[test]
    fn unchanged_values_are_not_reported_as_filled() {
        let bean = catalogue_autofill(
            &Bean::new("bean:1".into(), String::new(), 0),
            &entry(),
            false,
            false,
        )
        .bean;
        let again = catalogue_autofill(&bean, &entry(), true, true);
        assert!(
            again.filled.iter().all(|f| f == "roaster"),
            "{:?}",
            again.filled
        );
    }

    #[test]
    fn unknown_roast_label_and_missing_roaster_id_are_ignored() {
        let mut e = entry();
        e.roast_level = Some("Nordic".into());
        e.canonical_roaster_id = String::new();
        let r = catalogue_autofill(&Bean::new("b".into(), String::new(), 0), &e, false, false);
        assert_eq!(r.bean.roast_level, None);
        assert_eq!(r.bean.canonical_roaster_id, None);
    }

    #[test]
    fn json_facades_round_trip() {
        let page: CataloguePage =
            serde_json::from_str(&parse_catalogue_coffee_bags_json(&body().to_string()).unwrap())
                .unwrap();
        let bean = Bean::new("bean:1".into(), String::new(), 0);
        let out = catalogue_autofill_json(
            &serde_json::to_string(&bean).unwrap(),
            &serde_json::to_string(&page.entries[0]).unwrap(),
            false,
            false,
        )
        .unwrap();
        let r: CatalogueAutofill = serde_json::from_str(&out).unwrap();
        assert_eq!(r.bean.canonical_coffee_bag_id.as_deref(), Some("cb-1"));
        let v: Value = serde_json::from_str(&out).unwrap();
        assert_eq!(v["roasterName"], "Onyx Coffee Lab");
        assert_eq!(v["bean"]["canonicalCoffeeBagId"], "cb-1");
        assert!(catalogue_autofill_json("{}", "{}", false, false).is_err());
    }
}

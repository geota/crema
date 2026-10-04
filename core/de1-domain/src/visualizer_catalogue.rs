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
//! - [`catalogue_clashes`] lists the fields a pick would clash on — fields
//!   the user filled where the catalogue has a *different* non-empty value
//!   — as [`CatalogueField`] ids, so a shell can ask "Keep mine" / "Use
//!   catalogue" before applying. [`catalogue_pick`] applies a bag pick in the
//!   chosen mode, including the roaster the bag is filed under: an existing
//!   row linked to the catalogue roaster or with a matching name
//!   ([`find_catalogue_roaster`]), else a seed to create.
//! - [`parse_catalogue_roasters`], [`catalogue_roaster_clashes`] and
//!   [`catalogue_roaster_autofill`] do the same for the roaster form's search
//!   (`GET /canonical_roasters`). A roaster pick sets the roaster's
//!   `catalogue_roaster_id`; its local duplicate-of pointer
//!   (`canonical_roaster_id`) is never read or written here.

use crate::bean::{Bean, Roaster};
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
    let (count, page, pages) = paging(body);
    CataloguePage {
        entries,
        count,
        page,
        pages,
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
    let (bean, mut filled) = fill_bag_fields(bean, entry, replace_all);
    let roaster_name = Some(entry.roaster_name.trim())
        .filter(|n| !n.is_empty() && (!roaster_set || replace_all))
        .map(str::to_owned);
    if roaster_name.is_some() {
        insert_roaster_label(&mut filled);
    }
    CatalogueAutofill {
        bean,
        roaster_name,
        filled,
    }
}

/// `"roaster"` goes right after `"name"` (form order) in a `filled` list.
fn insert_roaster_label(filled: &mut Vec<String>) {
    let pos = usize::from(filled.first().is_some_and(|f| f == "name"));
    filled.insert(pos, "roaster".to_owned());
}

/// The bag's own fields (everything but the roaster) under the fill-empty /
/// `replace_all` rule, plus the catalogue links. Shared by
/// [`catalogue_autofill`] and [`catalogue_pick`].
fn fill_bag_fields(
    bean: &Bean,
    entry: &CatalogueCoffeeBag,
    replace_all: bool,
) -> (Bean, Vec<String>) {
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

    let o = &mut out.origin;
    let origin: [(&mut Option<String>, Option<&String>, &str); 7] = [
        (&mut o.country, entry.country.as_ref(), "origin.country"),
        (&mut o.region, entry.region.as_ref(), "origin.region"),
        (&mut o.farmer, entry.farmer.as_ref(), "origin.farmer"),
        (&mut o.variety, entry.variety.as_ref(), "origin.variety"),
        (
            &mut o.elevation,
            entry.elevation.as_ref(),
            "origin.elevation",
        ),
        (
            &mut o.processing,
            entry.processing.as_ref(),
            "origin.processing",
        ),
        (
            &mut o.harvest_time,
            entry.harvest_time.as_ref(),
            "origin.harvestTime",
        ),
    ];
    for (slot, value, label) in origin {
        put_opt(slot, value, replace_all, label, &mut filled);
    }

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
    (out, filled)
}

// ── Clashes ──────────────────────────────────────────────────────────────

/// A form field a catalogue pick can fill — the ids a clash list is made of.
/// Both shells map them to their own human labels ("Roast level",
/// "Roaster website", …). Declaration order is form order: clash lists come
/// out sorted by it.
///
/// `Roaster*` are fields of the local **roaster row** (the roaster form's
/// name / website / country, or — in a bag pick — the matched roaster's);
/// [`CatalogueField::Roaster`] is the bag form's roaster *input* (which
/// roaster the bag is filed under).
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum CatalogueField {
    /// Bag name.
    Name,
    /// The bag form's roaster input.
    Roaster,
    /// Origin country.
    Country,
    /// Origin region.
    Region,
    /// Farmer / producer.
    Farmer,
    /// Cultivar / variety.
    Variety,
    /// Elevation.
    Elevation,
    /// Process.
    Processing,
    /// Harvest time.
    HarvestTime,
    /// Roast level (compared after mapping the catalogue band to 1..10).
    RoastLevel,
    /// Tasting notes.
    TastingNotes,
    /// The bag's buy-again URL.
    Url,
    /// The roaster row's name.
    RoasterName,
    /// The roaster row's website.
    RoasterWebsite,
    /// The roaster row's country / location.
    RoasterCountry,
}

/// Trimmed, non-blank.
fn present(v: Option<&str>) -> Option<&str> {
    v.map(str::trim).filter(|s| !s.is_empty())
}

/// The clash test for one text field: the user filled it, the catalogue has
/// a value, and the two differ after trimming and case-folding. A blank on
/// either side is never a clash.
fn text_clash(user: Option<&str>, catalogue: Option<&str>) -> bool {
    match (present(user), present(catalogue)) {
        (Some(u), Some(c)) => u.to_lowercase() != c.to_lowercase(),
        _ => false,
    }
}

/// The roaster-name match key: case-folded, apostrophes and dots dropped,
/// other punctuation read as a space, whitespace trimmed and collapsed —
/// `"Onyx  Coffee Lab."` and `"onyx coffee lab"` share a key. A superset of
/// the duplicate detector's trim + lowercase grouping
/// ([`crate::detect_roaster_duplicates`]); no generic-suffix stripping
/// ("Coffee", "Roasters"), so near-misses stay separate rows for the
/// duplicate banner to suggest instead of being auto-picked.
#[must_use]
pub fn roaster_match_key(name: &str) -> String {
    let mut spaced = String::with_capacity(name.len());
    for c in name.chars().flat_map(char::to_lowercase) {
        if c.is_alphanumeric() {
            spaced.push(c);
        } else if matches!(c, '\'' | '\u{2019}' | '.') {
            // "Joe's" == "Joes"; "A.M." == "am".
        } else {
            spaced.push(' ');
        }
    }
    spaced.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Two roaster names name the same roaster (equal [`roaster_match_key`]s;
/// blank never matches).
fn same_roaster_name(a: &str, b: &str) -> bool {
    let k = roaster_match_key(a);
    !k.is_empty() && k == roaster_match_key(b)
}

/// The bag's own clashes (everything but the roaster input).
fn bag_clashes(bean: &Bean, entry: &CatalogueCoffeeBag) -> Vec<CatalogueField> {
    let o = &bean.origin;
    let pairs = [
        (
            CatalogueField::Name,
            Some(bean.name.as_str()),
            Some(entry.name.as_str()),
        ),
        (
            CatalogueField::Country,
            o.country.as_deref(),
            entry.country.as_deref(),
        ),
        (
            CatalogueField::Region,
            o.region.as_deref(),
            entry.region.as_deref(),
        ),
        (
            CatalogueField::Farmer,
            o.farmer.as_deref(),
            entry.farmer.as_deref(),
        ),
        (
            CatalogueField::Variety,
            o.variety.as_deref(),
            entry.variety.as_deref(),
        ),
        (
            CatalogueField::Elevation,
            o.elevation.as_deref(),
            entry.elevation.as_deref(),
        ),
        (
            CatalogueField::Processing,
            o.processing.as_deref(),
            entry.processing.as_deref(),
        ),
        (
            CatalogueField::HarvestTime,
            o.harvest_time.as_deref(),
            entry.harvest_time.as_deref(),
        ),
        (
            CatalogueField::TastingNotes,
            Some(bean.tasting_notes.as_str()),
            entry.tasting_notes.as_deref(),
        ),
        (
            CatalogueField::Url,
            bean.url.as_deref(),
            entry.url.as_deref(),
        ),
    ];
    let mut out: Vec<CatalogueField> = pairs
        .into_iter()
        .filter(|(_, user, cat)| text_clash(*user, *cat))
        .map(|(f, _, _)| f)
        .collect();
    if let (Some(mine), Some(theirs)) = (
        bean.roast_level,
        roast_level_from_wire(entry.roast_level.as_deref()),
    ) && mine != theirs
    {
        out.push(CatalogueField::RoastLevel);
    }
    out
}

// ── Catalogue roasters ───────────────────────────────────────────────────

/// One `CanonicalRoasterSummary` row (`GET /canonical_roasters`), trimmed,
/// blank → `None`.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CatalogueRoaster {
    /// Catalogue roaster id (`catalogue_roaster_id` on a local roaster).
    pub id: String,
    /// Roaster name.
    pub name: String,
    /// Roaster website.
    #[serde(default)]
    pub website: Option<String>,
    /// Country.
    #[serde(default)]
    pub country: Option<String>,
}

/// One page of catalogue roaster search results.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CatalogueRoasterPage {
    /// The rows, in server order. Malformed rows are dropped.
    pub entries: Vec<CatalogueRoaster>,
    /// Total matches across all pages (`paging.count`; `0` when absent).
    pub count: u32,
    /// 1-based page number (`paging.page`; `1` when absent).
    pub page: u32,
    /// Total page count (`paging.pages`; `1` when absent).
    pub pages: u32,
}

/// The result of [`catalogue_roaster_autofill`].
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CatalogueRoasterAutofill {
    /// The roaster with the pick applied (`catalogue_roaster_id` set).
    pub roaster: Roaster,
    /// The fields that changed (excludes the link id).
    pub filled: Vec<CatalogueField>,
}

/// Parse a `GET /canonical_roasters` body (`CanonicalRoasterListResponse`).
/// Never fails: a non-object body is an empty page, a row without a string
/// `id` or a non-blank `name` is skipped.
#[must_use]
pub fn parse_catalogue_roasters(body: &Value) -> CatalogueRoasterPage {
    let entries = body
        .get("data")
        .and_then(Value::as_array)
        .map(|rows| {
            rows.iter()
                .filter_map(|v| {
                    let obj = v.as_object()?;
                    Some(CatalogueRoaster {
                        id: clean(obj.get("id"))?,
                        name: clean(obj.get("name"))?,
                        website: clean(obj.get("website")),
                        country: clean(obj.get("country")),
                    })
                })
                .collect()
        })
        .unwrap_or_default();
    let (count, page, pages) = paging(body);
    CatalogueRoasterPage {
        entries,
        count,
        page,
        pages,
    }
}

/// `(count, page, pages)` from a list response's `paging` (defaults 0/1/1).
fn paging(body: &Value) -> (u32, u32, u32) {
    let paging = body.get("paging");
    let num = |k: &str, dflt: u32| {
        paging
            .and_then(|p| p.get(k))
            .and_then(Value::as_u64)
            .and_then(|n| u32::try_from(n).ok())
            .unwrap_or(dflt)
    };
    (
        num("count", 0),
        num("page", 1).max(1),
        num("pages", 1).max(1),
    )
}

/// Which of `roaster`'s filled fields the catalogue roaster has a different
/// non-empty value for ([`CatalogueField::RoasterName`] / `RoasterWebsite` /
/// `RoasterCountry`). Names compare by [`roaster_match_key`]; the rest after
/// trimming and case-folding.
#[must_use]
pub fn catalogue_roaster_clashes(
    roaster: &Roaster,
    entry: &CatalogueRoaster,
) -> Vec<CatalogueField> {
    let mut out = Vec::new();
    if present(Some(&roaster.name)).is_some()
        && present(Some(&entry.name)).is_some()
        && !same_roaster_name(&roaster.name, &entry.name)
    {
        out.push(CatalogueField::RoasterName);
    }
    if text_clash(roaster.website.as_deref(), entry.website.as_deref()) {
        out.push(CatalogueField::RoasterWebsite);
    }
    if text_clash(roaster.country.as_deref(), entry.country.as_deref()) {
        out.push(CatalogueField::RoasterCountry);
    }
    out
}

/// Apply a picked catalogue roaster onto `roaster` — the same rule as
/// [`catalogue_autofill`]: by default only empty name / website / country
/// are filled; `replace_all` overwrites them too, but a catalogue blank
/// never erases. `catalogue_roaster_id` is set to the pick's id (the pick is
/// the link). The local duplicate-of pointer (`canonical_roaster_id`) and
/// `updated_at` are never touched.
#[must_use]
pub fn catalogue_roaster_autofill(
    roaster: &Roaster,
    entry: &CatalogueRoaster,
    replace_all: bool,
) -> CatalogueRoasterAutofill {
    let mut out = roaster.clone();
    let mut filled = Vec::new();
    if let Some(name) = present(Some(&entry.name))
        && (present(Some(&out.name)).is_none() || replace_all)
        && out.name != name
    {
        out.name = name.to_owned();
        filled.push(CatalogueField::RoasterName);
    }
    for (slot, value, field) in [
        (
            &mut out.website,
            &entry.website,
            CatalogueField::RoasterWebsite,
        ),
        (
            &mut out.country,
            &entry.country,
            CatalogueField::RoasterCountry,
        ),
    ] {
        let Some(v) = present(value.as_deref()) else {
            continue;
        };
        if (present(slot.as_deref()).is_none() || replace_all) && slot.as_deref() != Some(v) {
            *slot = Some(v.to_owned());
            filled.push(field);
        }
    }
    if let Some(id) = present(Some(&entry.id)) {
        out.catalogue_roaster_id = Some(id.to_owned());
    }
    CatalogueRoasterAutofill {
        roaster: out,
        filled,
    }
}

/// The local roaster a catalogue roaster resolves to, in order:
///
/// 1. a live row already linked to it (`catalogue_roaster_id == id`);
/// 2. a live row whose name shares its [`roaster_match_key`] and isn't
///    linked to a *different* catalogue roaster.
///
/// Soft-deleted rows never match. No fuzzy scoring: a likely-but-unsure
/// match is left to the duplicate banner. The duplicate-of pointer
/// (`canonical_roaster_id`) is not consulted.
#[must_use]
pub fn find_catalogue_roaster<'a>(
    roasters: &'a [Roaster],
    catalogue_roaster_id: &str,
    name: &str,
) -> Option<&'a Roaster> {
    let id = catalogue_roaster_id.trim();
    let live = || roasters.iter().filter(|r| r.deleted_at.is_none());
    if !id.is_empty()
        && let Some(r) = live().find(|r| r.catalogue_roaster_id.as_deref() == Some(id))
    {
        return Some(r);
    }
    live().find(|r| {
        same_roaster_name(&r.name, name)
            && present(r.catalogue_roaster_id.as_deref()).is_none_or(|linked| linked == id)
    })
}

// ── Bag pick (bag fields + roaster resolution) ───────────────────────────

/// What a bag pick does to the bag's roaster — see [`catalogue_pick`].
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CataloguePickRoaster {
    /// The roaster name to show in the roaster input.
    pub name: String,
    /// `false`: `roaster` is an existing local row (matched by catalogue link
    /// or name) with the catalogue fields applied — save it and file the bag
    /// under it. `true`: no local match — `roaster` is a seed (blank `id`,
    /// zero timestamps) carrying the catalogue name / website / country and
    /// link, for the shell to create (at Save, for a new bag).
    pub is_new: bool,
    /// The updated existing row, or the seed.
    pub roaster: Roaster,
    /// Roaster-row fields that changed / were seeded.
    pub filled: Vec<CatalogueField>,
}

/// The result of [`catalogue_pick`].
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CataloguePick {
    /// The bean with the bag fields + catalogue links applied (`roaster_id`
    /// untouched — the shell files it under [`CataloguePick::roaster`]).
    pub bean: Bean,
    /// camelCase names of the bag fields that changed, as
    /// [`CatalogueAutofill::filled`] (`"roaster"` when the input changes).
    pub filled: Vec<String>,
    /// The roaster to file the bag under, or `None` = leave the bag's roaster
    /// alone (the catalogue names none, or the user kept a different one).
    pub roaster: Option<CataloguePickRoaster>,
}

/// The catalogue roaster behind a bag pick: the fetched record when it's the
/// bag's roaster, else name + id from the bag row (website / country
/// unknown). `None` when the bag names no roaster.
fn pick_catalogue_roaster(
    entry: &CatalogueCoffeeBag,
    fetched: Option<&CatalogueRoaster>,
) -> Option<CatalogueRoaster> {
    let id = entry.canonical_roaster_id.trim();
    if let Some(r) = fetched.filter(|r| !id.is_empty() && r.id == id) {
        return Some(r.clone());
    }
    present(Some(&entry.roaster_name)).map(|name| CatalogueRoaster {
        id: id.to_owned(),
        name: name.to_owned(),
        website: None,
        country: None,
    })
}

/// The roaster side of a bag pick.
struct RoasterPlan<'a> {
    catalogue: CatalogueRoaster,
    matched: Option<&'a Roaster>,
    /// The roaster input names a different roaster than the catalogue's.
    input_clash: bool,
}

fn plan_roaster<'a>(
    entry: &CatalogueCoffeeBag,
    roaster_input: &str,
    roasters: &'a [Roaster],
    fetched: Option<&CatalogueRoaster>,
) -> Option<RoasterPlan<'a>> {
    let catalogue = pick_catalogue_roaster(entry, fetched)?;
    let matched = find_catalogue_roaster(roasters, &catalogue.id, &catalogue.name);
    let input_clash = present(Some(roaster_input)).is_some()
        && !same_roaster_name(roaster_input, &catalogue.name)
        && matched.is_none_or(|m| !same_roaster_name(roaster_input, &m.name));
    Some(RoasterPlan {
        catalogue,
        matched,
        input_clash,
    })
}

/// Every clash a bag pick would raise — the bag's own fields, the roaster
/// input ([`CatalogueField::Roaster`]: it holds a different roaster's name),
/// and the matched local roaster's name / website / country — merged into
/// one list in form order, for one dialog.
///
/// A clash is a field the user filled where the catalogue has a different
/// non-empty value: equal after trimming + case-folding is not a clash, the
/// roast level clashes only when the mapped level differs, and a catalogue
/// blank never clashes. `roasters` is the local directory and `fetched` the
/// catalogue roaster record (`GET /canonical_roasters`) when the shell has
/// it — see [`catalogue_pick`].
#[must_use]
pub fn catalogue_clashes(
    bean: &Bean,
    entry: &CatalogueCoffeeBag,
    roaster_input: &str,
    roasters: &[Roaster],
    fetched: Option<&CatalogueRoaster>,
) -> Vec<CatalogueField> {
    let mut out = bag_clashes(bean, entry);
    if let Some(plan) = plan_roaster(entry, roaster_input, roasters, fetched) {
        if plan.input_clash {
            out.push(CatalogueField::Roaster);
        }
        if let Some(m) = plan.matched {
            out.extend(catalogue_roaster_clashes(m, &plan.catalogue));
        }
    }
    out.sort_unstable();
    out.dedup();
    out
}

/// Apply a picked catalogue bag: the bag fields (as [`catalogue_autofill`])
/// plus the roaster it's filed under. `replace_all` is the clash dialog's
/// answer ("Use catalogue"); with no clashes the shell passes `false`.
///
/// Roaster resolution ([`find_catalogue_roaster`]): an existing row linked to
/// the bag's catalogue roaster, else one with a matching name; it gets the
/// catalogue name / website / country under the same rule and its
/// `catalogue_roaster_id` link. No match → a seed with every catalogue field
/// for the shell to create. When the roaster input names a different roaster
/// (a [`CatalogueField::Roaster`] clash) and `replace_all` is off, the bag's
/// roaster is left alone. `fetched` is the catalogue roaster record (for its
/// website / country); without it, name + link only.
#[must_use]
pub fn catalogue_pick(
    bean: &Bean,
    entry: &CatalogueCoffeeBag,
    roaster_input: &str,
    roasters: &[Roaster],
    fetched: Option<&CatalogueRoaster>,
    replace_all: bool,
) -> CataloguePick {
    let (bean, mut filled) = fill_bag_fields(bean, entry, replace_all);
    let roaster = plan_roaster(entry, roaster_input, roasters, fetched)
        .filter(|plan| !plan.input_clash || replace_all)
        .map(|plan| match plan.matched {
            Some(m) => {
                let a = catalogue_roaster_autofill(m, &plan.catalogue, replace_all);
                CataloguePickRoaster {
                    name: a.roaster.name.clone(),
                    is_new: false,
                    roaster: a.roaster,
                    filled: a.filled,
                }
            }
            None => {
                // Keep the user's spelling of the same name unless replacing.
                let name = present(Some(roaster_input))
                    .filter(|_| !replace_all)
                    .unwrap_or(&plan.catalogue.name)
                    .to_owned();
                let a = catalogue_roaster_autofill(
                    &Roaster::new(String::new(), name, 0),
                    &plan.catalogue,
                    false,
                );
                CataloguePickRoaster {
                    name: a.roaster.name.clone(),
                    is_new: true,
                    filled: a
                        .filled
                        .into_iter()
                        .filter(|f| *f != CatalogueField::RoasterName)
                        .collect(),
                    roaster: a.roaster,
                }
            }
        });
    if roaster
        .as_ref()
        .is_some_and(|r| r.name != roaster_input.trim())
    {
        insert_roaster_label(&mut filled);
    }
    CataloguePick {
        bean,
        filled,
        roaster,
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

/// Parse an optional JSON value: `""` / `"null"` → `None`.
fn opt_json<T: serde::de::DeserializeOwned>(json: &str) -> Result<Option<T>, String> {
    if json.trim().is_empty() {
        return Ok(None);
    }
    serde_json::from_str(json).map_err(|e| e.to_string())
}

fn from_json<T: serde::de::DeserializeOwned>(json: &str) -> Result<T, String> {
    serde_json::from_str(json).map_err(|e| e.to_string())
}

fn to_json<T: Serialize>(v: &T) -> Result<String, String> {
    serde_json::to_string(v).map_err(|e| e.to_string())
}

/// JSON-bridged [`catalogue_clashes`]. Inputs: a [`Bean`] JSON, a
/// [`CatalogueCoffeeBag`] JSON, the roaster input text, a `Roaster[]` JSON
/// and a [`CatalogueRoaster`] JSON (`"null"` / `""` = not fetched). Output: a
/// `CatalogueField[]` JSON.
///
/// # Errors
/// The JSON parse / serialise error string on a malformed input.
pub fn catalogue_clashes_json(
    bean_json: &str,
    entry_json: &str,
    roaster_input: &str,
    roasters_json: &str,
    fetched_json: &str,
) -> Result<String, String> {
    let bean: Bean = from_json(bean_json)?;
    let entry: CatalogueCoffeeBag = from_json(entry_json)?;
    let roasters: Vec<Roaster> = from_json(roasters_json)?;
    let fetched: Option<CatalogueRoaster> = opt_json(fetched_json)?;
    to_json(&catalogue_clashes(
        &bean,
        &entry,
        roaster_input,
        &roasters,
        fetched.as_ref(),
    ))
}

/// JSON-bridged [`catalogue_pick`] — inputs as [`catalogue_clashes_json`]
/// plus `replace_all`. Output: a [`CataloguePick`] JSON.
///
/// # Errors
/// The JSON parse / serialise error string on a malformed input.
pub fn catalogue_pick_json(
    bean_json: &str,
    entry_json: &str,
    roaster_input: &str,
    roasters_json: &str,
    fetched_json: &str,
    replace_all: bool,
) -> Result<String, String> {
    let bean: Bean = from_json(bean_json)?;
    let entry: CatalogueCoffeeBag = from_json(entry_json)?;
    let roasters: Vec<Roaster> = from_json(roasters_json)?;
    let fetched: Option<CatalogueRoaster> = opt_json(fetched_json)?;
    to_json(&catalogue_pick(
        &bean,
        &entry,
        roaster_input,
        &roasters,
        fetched.as_ref(),
        replace_all,
    ))
}

/// JSON-bridged [`parse_catalogue_roasters`]. Input: the raw response body.
/// Output: a [`CatalogueRoasterPage`] JSON.
///
/// # Errors
/// The JSON parse error string when `body_json` isn't JSON.
pub fn parse_catalogue_roasters_json(body_json: &str) -> Result<String, String> {
    let body: Value = from_json(body_json)?;
    to_json(&parse_catalogue_roasters(&body))
}

/// JSON-bridged [`catalogue_roaster_clashes`]. Inputs: a [`Roaster`] JSON and
/// a [`CatalogueRoaster`] JSON. Output: a `CatalogueField[]` JSON.
///
/// # Errors
/// The JSON parse / serialise error string on a malformed input.
pub fn catalogue_roaster_clashes_json(
    roaster_json: &str,
    entry_json: &str,
) -> Result<String, String> {
    let roaster: Roaster = from_json(roaster_json)?;
    let entry: CatalogueRoaster = from_json(entry_json)?;
    to_json(&catalogue_roaster_clashes(&roaster, &entry))
}

/// JSON-bridged [`catalogue_roaster_autofill`]. Inputs: a [`Roaster`] JSON and
/// a [`CatalogueRoaster`] JSON. Output: a [`CatalogueRoasterAutofill`] JSON.
///
/// # Errors
/// The JSON parse / serialise error string on a malformed input.
pub fn catalogue_roaster_autofill_json(
    roaster_json: &str,
    entry_json: &str,
    replace_all: bool,
) -> Result<String, String> {
    let roaster: Roaster = from_json(roaster_json)?;
    let entry: CatalogueRoaster = from_json(entry_json)?;
    to_json(&catalogue_roaster_autofill(&roaster, &entry, replace_all))
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

    // ── clashes ──────────────────────────────────────────────────────────

    use CatalogueField as F;

    fn roaster(id: &str, name: &str) -> Roaster {
        Roaster::new(id.into(), name.into(), 0)
    }

    fn onyx_record() -> CatalogueRoaster {
        CatalogueRoaster {
            id: "cr-1".into(),
            name: "Onyx Coffee Lab".into(),
            website: Some("https://onyxcoffeelab.com".into()),
            country: Some("USA".into()),
        }
    }

    #[test]
    fn no_clash_when_only_empty_fields_would_fill() {
        let bean = Bean::new("bean:1".into(), String::new(), 0);
        assert!(catalogue_clashes(&bean, &entry(), "", &[], None).is_empty());
        // Same values already typed — still no clash.
        let filled = catalogue_pick(&bean, &entry(), "", &[], None, false).bean;
        assert!(catalogue_clashes(&filled, &entry(), "Onyx Coffee Lab", &[], None).is_empty());
    }

    #[test]
    fn one_clash_is_listed() {
        let mut bean = Bean::new("bean:1".into(), String::new(), 0);
        bean.origin.processing = Some("Natural".into());
        bean.origin.country = Some("Ethiopia".into()); // same → not a clash
        assert_eq!(
            catalogue_clashes(&bean, &entry(), "", &[], None),
            [F::Processing]
        );
    }

    #[test]
    fn case_and_whitespace_differences_are_not_clashes() {
        let mut bean = Bean::new("bean:1".into(), "  ethiopia GUJI hambela ".into(), 0);
        bean.origin.processing = Some("washed".into());
        bean.tasting_notes = "BERGAMOT, PEACH  ".into();
        assert!(catalogue_clashes(&bean, &entry(), " onyx coffee lab", &[], None).is_empty());
    }

    #[test]
    fn catalogue_blanks_never_clash() {
        let mut bean = Bean::new("bean:1".into(), String::new(), 0);
        bean.origin.farmer = Some("Tarekech".into()); // catalogue farmer: null
        bean.origin.harvest_time = Some("2025".into()); // catalogue: ""
        assert!(catalogue_clashes(&bean, &entry(), "", &[], None).is_empty());
    }

    #[test]
    fn roast_level_clashes_on_the_mapped_level() {
        let mut bean = Bean::new("bean:1".into(), String::new(), 0);
        bean.roast_level = Some(2); // "Light" maps to 2
        assert!(catalogue_clashes(&bean, &entry(), "", &[], None).is_empty());
        bean.roast_level = Some(8);
        assert_eq!(
            catalogue_clashes(&bean, &entry(), "", &[], None),
            [F::RoastLevel]
        );
        let mut e = entry();
        e.roast_level = Some("Nordic".into()); // unmapped → blank → no clash
        assert!(catalogue_clashes(&bean, &e, "", &[], None).is_empty());
    }

    #[test]
    fn roaster_input_clashes_on_a_different_name() {
        let bean = Bean::new("bean:1".into(), String::new(), 0);
        assert_eq!(
            catalogue_clashes(&bean, &entry(), "Sey", &[], None),
            [F::Roaster]
        );
        // Punctuation / spacing variants are the same roaster.
        assert!(catalogue_clashes(&bean, &entry(), "onyx  coffee lab.", &[], None).is_empty());
        // The input names the row linked to the catalogue roaster → not a
        // roaster-input clash; that row's own name differs, though.
        let mut mine = roaster("roaster:1", "Onyx");
        mine.catalogue_roaster_id = Some("cr-1".into());
        assert_eq!(
            catalogue_clashes(&bean, &entry(), "Onyx", &[mine], None),
            [F::RoasterName]
        );
    }

    #[test]
    fn clashes_come_out_in_form_order() {
        let mut bean = Bean::new("bean:1".into(), "Mine".into(), 0);
        bean.tasting_notes = "jammy".into();
        bean.roast_level = Some(9);
        bean.origin.processing = Some("Natural".into());
        assert_eq!(
            catalogue_clashes(&bean, &entry(), "Sey", &[], None),
            [
                F::Name,
                F::Roaster,
                F::Processing,
                F::RoastLevel,
                F::TastingNotes
            ]
        );
    }

    // ── roaster resolution on a bag pick ────────────────────────────────

    #[test]
    fn roaster_match_key_folds_case_space_and_punctuation() {
        assert_eq!(
            roaster_match_key("  Onyx   Coffee Lab. "),
            "onyx coffee lab"
        );
        assert_eq!(roaster_match_key("Joe's"), roaster_match_key("joes"));
        assert_eq!(roaster_match_key("Sey-Coffee"), "sey coffee");
        // No generic-suffix stripping.
        assert_ne!(roaster_match_key("Onyx"), roaster_match_key("Onyx Coffee"));
    }

    #[test]
    fn link_match_beats_name_match() {
        let by_name = roaster("roaster:a", "Onyx Coffee Lab");
        let mut by_link = roaster("roaster:b", "Onyx (renamed)");
        by_link.catalogue_roaster_id = Some("cr-1".into());
        let rs = [by_name, by_link];
        let hit = find_catalogue_roaster(&rs, "cr-1", "Onyx Coffee Lab").unwrap();
        assert_eq!(hit.id, "roaster:b");
    }

    #[test]
    fn normalised_name_match_skips_deleted_and_differently_linked_rows() {
        let mut deleted = roaster("roaster:d", "Onyx Coffee Lab");
        deleted.deleted_at = Some(1);
        let mut other_link = roaster("roaster:o", "onyx coffee lab");
        other_link.catalogue_roaster_id = Some("cr-9".into());
        let plain = roaster("roaster:p", "ONYX  coffee lab.");
        let rs = [deleted, other_link, plain];
        assert_eq!(
            find_catalogue_roaster(&rs, "cr-1", "Onyx Coffee Lab")
                .unwrap()
                .id,
            "roaster:p"
        );
        assert!(find_catalogue_roaster(&rs[..2], "cr-1", "Onyx Coffee Lab").is_none());
    }

    #[test]
    fn bag_pick_files_under_the_matched_roaster_and_fills_its_empties() {
        let mut mine = roaster("roaster:1", "Onyx Coffee Lab");
        mine.canonical_roaster_id = Some("roaster:canon".into());
        let bean = Bean::new("bean:1".into(), String::new(), 0);
        let p = catalogue_pick(&bean, &entry(), "", &[mine], Some(&onyx_record()), false);
        let r = p.roaster.unwrap();
        assert!(!r.is_new);
        assert_eq!(r.roaster.id, "roaster:1");
        assert_eq!(r.name, "Onyx Coffee Lab");
        assert_eq!(
            r.roaster.website.as_deref(),
            Some("https://onyxcoffeelab.com")
        );
        assert_eq!(r.roaster.country.as_deref(), Some("USA"));
        assert_eq!(r.roaster.catalogue_roaster_id.as_deref(), Some("cr-1"));
        assert_eq!(r.filled, [F::RoasterWebsite, F::RoasterCountry]);
        // The duplicate-of pointer is left exactly as it was.
        assert_eq!(
            r.roaster.canonical_roaster_id.as_deref(),
            Some("roaster:canon")
        );
        assert!(p.filled.contains(&"roaster".to_owned()));
    }

    #[test]
    fn no_match_seeds_a_new_roaster_with_every_catalogue_field() {
        let bean = Bean::new("bean:1".into(), String::new(), 0);
        let p = catalogue_pick(
            &bean,
            &entry(),
            "",
            &[roaster("roaster:x", "Sey")],
            Some(&onyx_record()),
            false,
        );
        let r = p.roaster.unwrap();
        assert!(r.is_new);
        assert_eq!(r.roaster.id, "");
        assert_eq!(r.roaster.name, "Onyx Coffee Lab");
        assert_eq!(
            r.roaster.website.as_deref(),
            Some("https://onyxcoffeelab.com")
        );
        assert_eq!(r.roaster.country.as_deref(), Some("USA"));
        assert_eq!(r.roaster.catalogue_roaster_id.as_deref(), Some("cr-1"));
        assert_eq!(r.roaster.canonical_roaster_id, None);
        // Without the fetched record: name + link only.
        let p = catalogue_pick(&bean, &entry(), "", &[], None, false);
        let r = p.roaster.unwrap();
        assert_eq!((r.roaster.website, r.roaster.country), (None, None));
        assert_eq!(r.roaster.catalogue_roaster_id.as_deref(), Some("cr-1"));
        // A fetched record for a different roaster is ignored.
        let mut stray = onyx_record();
        stray.id = "cr-9".into();
        let r = catalogue_pick(&bean, &entry(), "", &[], Some(&stray), false)
            .roaster
            .unwrap();
        assert_eq!(r.roaster.website, None);
    }

    #[test]
    fn roaster_clashes_merge_into_the_bag_clash_list() {
        let mut mine = roaster("roaster:1", "Onyx");
        mine.catalogue_roaster_id = Some("cr-1".into());
        mine.website = Some("https://onyx.example".into());
        mine.country = Some("usa".into()); // case-equal → no clash
        let mut bean = Bean::new("bean:1".into(), String::new(), 0);
        bean.origin.processing = Some("Natural".into());
        let rs = [mine];
        assert_eq!(
            catalogue_clashes(&bean, &entry(), "Onyx", &rs, Some(&onyx_record())),
            [F::Processing, F::RoasterName, F::RoasterWebsite]
        );
        // Keep mine: the roaster's filled fields survive.
        let keep = catalogue_pick(&bean, &entry(), "Onyx", &rs, Some(&onyx_record()), false);
        let r = keep.roaster.unwrap();
        assert_eq!(r.roaster.name, "Onyx");
        assert_eq!(r.roaster.website.as_deref(), Some("https://onyx.example"));
        assert_eq!(keep.bean.origin.processing.as_deref(), Some("Natural"));
        // Use catalogue: every listed field is replaced.
        let all = catalogue_pick(&bean, &entry(), "Onyx", &rs, Some(&onyx_record()), true);
        let r = all.roaster.unwrap();
        assert_eq!(r.name, "Onyx Coffee Lab");
        assert_eq!(
            r.roaster.website.as_deref(),
            Some("https://onyxcoffeelab.com")
        );
        assert_eq!(all.bean.origin.processing.as_deref(), Some("Washed"));
    }

    #[test]
    fn keep_mine_on_a_roaster_clash_leaves_the_bags_roaster_alone() {
        let bean = Bean::new("bean:1".into(), String::new(), 0);
        let rs = [roaster("roaster:1", "Onyx Coffee Lab")];
        let keep = catalogue_pick(&bean, &entry(), "Sey", &rs, None, false);
        assert!(keep.roaster.is_none());
        assert!(!keep.filled.contains(&"roaster".to_owned()));
        let all = catalogue_pick(&bean, &entry(), "Sey", &rs, None, true);
        assert_eq!(all.roaster.unwrap().roaster.id, "roaster:1");
    }

    // ── roaster form search ─────────────────────────────────────────────

    #[test]
    fn parses_catalogue_roasters() {
        let page = parse_catalogue_roasters(&json!({
            "data": [
                { "id": "cr-1", "name": " Onyx Coffee Lab ", "website": "https://onyx", "country": "" },
                { "id": "cr-2", "name": "" },
                { "name": "no id" }
            ],
            "paging": { "count": 1, "page": 1, "limit": 10, "pages": 1 }
        }));
        assert_eq!(page.entries.len(), 1);
        assert_eq!(page.entries[0].name, "Onyx Coffee Lab");
        assert_eq!(page.entries[0].country, None);
        assert_eq!(page.count, 1);
        assert!(parse_catalogue_roasters(&json!(null)).entries.is_empty());
    }

    #[test]
    fn roaster_form_clashes_and_autofill() {
        let mut mine = roaster("roaster:1", "");
        mine.country = Some("Canada".into());
        mine.canonical_roaster_id = Some("roaster:canon".into());
        assert_eq!(
            catalogue_roaster_clashes(&mine, &onyx_record()),
            [F::RoasterCountry]
        );
        let keep = catalogue_roaster_autofill(&mine, &onyx_record(), false);
        assert_eq!(keep.roaster.name, "Onyx Coffee Lab");
        assert_eq!(keep.roaster.country.as_deref(), Some("Canada"));
        assert_eq!(keep.roaster.catalogue_roaster_id.as_deref(), Some("cr-1"));
        assert_eq!(
            keep.roaster.canonical_roaster_id.as_deref(),
            Some("roaster:canon")
        );
        assert_eq!(keep.filled, [F::RoasterName, F::RoasterWebsite]);
        let all = catalogue_roaster_autofill(&mine, &onyx_record(), true);
        assert_eq!(all.roaster.country.as_deref(), Some("USA"));
        // A catalogue blank never erases.
        let mut blank = onyx_record();
        blank.website = None;
        mine.website = Some("https://mine".into());
        let r = catalogue_roaster_autofill(&mine, &blank, true);
        assert_eq!(r.roaster.website.as_deref(), Some("https://mine"));
    }

    #[test]
    fn clash_and_pick_json_facades_round_trip() {
        let mut bean = Bean::new("bean:1".into(), String::new(), 0);
        bean.origin.processing = Some("Natural".into());
        let bj = serde_json::to_string(&bean).unwrap();
        let ej = serde_json::to_string(&entry()).unwrap();
        let rj = serde_json::to_string(&vec![roaster("roaster:1", "Onyx Coffee Lab")]).unwrap();
        let fj = serde_json::to_string(&onyx_record()).unwrap();
        let clashes = catalogue_clashes_json(&bj, &ej, "", &rj, "null").unwrap();
        assert_eq!(clashes, r#"["processing"]"#);
        let pick: Value =
            serde_json::from_str(&catalogue_pick_json(&bj, &ej, "", &rj, &fj, true).unwrap())
                .unwrap();
        assert_eq!(pick["bean"]["origin"]["processing"], "Washed");
        assert_eq!(pick["roaster"]["isNew"], false);
        assert_eq!(pick["roaster"]["roaster"]["catalogueRoasterId"], "cr-1");
        assert_eq!(
            pick["roaster"]["filled"],
            json!(["roasterWebsite", "roasterCountry"])
        );
        let page: Value = serde_json::from_str(
            &parse_catalogue_roasters_json(r#"{"data":[{"id":"cr-1","name":"Onyx"}]}"#).unwrap(),
        )
        .unwrap();
        assert_eq!(page["entries"][0]["name"], "Onyx");
        let mine = serde_json::to_string(&roaster("roaster:1", "Sey")).unwrap();
        assert_eq!(
            catalogue_roaster_clashes_json(&mine, &fj).unwrap(),
            r#"["roasterName"]"#
        );
        let a: Value =
            serde_json::from_str(&catalogue_roaster_autofill_json(&mine, &fj, false).unwrap())
                .unwrap();
        assert_eq!(a["roaster"]["name"], "Sey");
        assert_eq!(a["roaster"]["website"], "https://onyxcoffeelab.com");
        assert!(catalogue_clashes_json("{}", &ej, "", "[]", "").is_err());
    }
}

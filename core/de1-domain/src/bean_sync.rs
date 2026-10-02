//! # bean-sync
//!
//! The pure halves of the Visualizer **bean / roaster sync** and the roaster
//! directory's **duplicate detection + merge** — lifted out of the web shell
//! (`services/bean-sync.ts`, `bean/visualizer-sync.ts`, `routes/beans`) so the
//! web and Android shells share one implementation.
//!
//! Sans-IO, like the rest of the core: the shells own the HTTP (the paginated
//! pulls, the POST / PATCH / DELETE, the 401 refresh, the Premium probe) and
//! the store mutations. What lives here:
//!
//! - [`coffee_bag_write_request`] / [`roaster_write_request`] — the
//!   `CoffeeBagWriteRequest` / `RoasterWriteRequest` envelopes
//!   (`{"coffee_bag": {...}}` / `{"roaster": {...}}`). The catalogue links
//!   (`canonical_coffee_bag_id` / `canonical_roaster_id`) are sent only when
//!   Crema has one: omitting the key leaves the remote value alone, so a PATCH
//!   from a bag Crema never linked can't unlink one the user linked on
//!   visualizer.coffee. The roaster's LOCAL duplicate-of pointer
//!   ([`Roaster::canonical_roaster_id`]) never leaves the device.
//! - [`resolve_roaster_catalogue_link`] — a roaster's catalogue link for a
//!   write: its own `catalogue_roaster_id`, else the catalogue roaster id of a
//!   live bean filed under it that was picked from the catalogue.
//! - [`merge_pulled_roaster`] — fold a reconciled remote roaster into the
//!   local row (the `update` / `bind` legs of [`crate::reconcile_roasters`]).
//! - [`plan_bean_push`] / [`plan_roaster_link_patches`] — which local rows the
//!   push legs write.
//! - [`detect_roaster_duplicates`] / [`plan_roaster_merge`] — the Roasters
//!   tab's merge suggestions and the merge bookkeeping.
//! - [`plan_roaster_delete`] — which bags a roaster delete detaches or
//!   deletes (cascade), and the Visualizer ids an "also delete on
//!   Visualizer" removes.

use crate::bean::{Bean, Roaster};
#[cfg(test)]
use crate::visualizer_wire::RoasterWire;
use crate::visualizer_wire::bean_to_wire;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};
use std::collections::{HashMap, HashSet};
use typeshare::typeshare;

/// `Some(s)` when `s` is a non-empty string (the TS truthiness test).
fn non_empty(s: Option<&String>) -> Option<&String> {
    s.filter(|v| !v.is_empty())
}

// ── Write envelopes ───────────────────────────────────────────────────────

/// The `CoffeeBagWriteRequest` body for `POST /coffee_bags` /
/// `PATCH /coffee_bags/{id}`: [`bean_to_wire`] wrapped in the
/// `{"coffee_bag": {...}}` envelope. `roaster_remote_id` is the bag's
/// roaster's Visualizer id (the shell resolves it). Unset fields are explicit
/// `null`s; `canonical_coffee_bag_id` is omitted when the bean has no
/// catalogue link. Mirrors the TS `bagBodyToWriteRequest(beanToWire(..))`.
#[must_use]
pub fn coffee_bag_write_request(bean: &Bean, roaster_remote_id: Option<&str>) -> Value {
    let w = bean_to_wire(bean, roaster_remote_id);
    let mut body = Map::new();
    body.insert("name".into(), json!(w.name));
    body.insert("roaster_id".into(), json!(w.roaster_id));
    if let Some(link) = non_empty(w.canonical_coffee_bag_id.as_ref()) {
        body.insert("canonical_coffee_bag_id".into(), json!(link));
    }
    for (key, value) in [
        ("roast_date", &w.roast_date),
        ("frozen_date", &w.frozen_date),
        ("defrosted_date", &w.defrosted_date),
        ("roast_level", &w.roast_level),
        ("country", &w.country),
        ("region", &w.region),
        ("farm", &w.farm),
        ("farmer", &w.farmer),
        ("variety", &w.variety),
        ("elevation", &w.elevation),
        ("processing", &w.processing),
        ("harvest_time", &w.harvest_time),
        ("quality_score", &w.quality_score),
        ("tasting_notes", &w.tasting_notes),
        ("place_of_purchase", &w.place_of_purchase),
        ("url", &w.url),
        ("notes", &w.notes),
    ] {
        body.insert(key.into(), json!(value));
    }
    body.insert("metadata".into(), w.metadata.unwrap_or(Value::Null));
    json!({ "coffee_bag": Value::Object(body) })
}

/// The `RoasterWriteRequest` body for `POST /roasters` /
/// `PATCH /roasters/{id}`: `{"roaster": {name, website, canonical_roaster_id?}}`.
/// `canonical_roaster_id` is the Visualizer catalogue link
/// ([`Roaster::catalogue_roaster_id`]), omitted when empty; the local
/// duplicate-of pointer ([`Roaster::canonical_roaster_id`]) is never sent.
/// Mirrors the TS `roasterBodyToWriteRequest(roasterToWire(..))`.
#[must_use]
pub fn roaster_write_request(roaster: &Roaster) -> Value {
    let mut body = Map::new();
    body.insert("name".into(), json!(roaster.name));
    body.insert("website".into(), json!(roaster.website));
    if let Some(link) = non_empty(roaster.catalogue_roaster_id.as_ref()) {
        body.insert("canonical_roaster_id".into(), json!(link));
    }
    json!({ "roaster": Value::Object(body) })
}

/// A roaster's catalogue link for a write: its own `catalogue_roaster_id`,
/// else the `canonical_roaster_id` (catalogue roaster id) of the first live
/// bean filed under it that was picked from the catalogue. `None` = no link.
/// Mirrors `withCatalogueLink` in the web `BeanSync.runSync`.
#[must_use]
pub fn resolve_roaster_catalogue_link(roaster: &Roaster, beans: &[Bean]) -> Option<String> {
    if let Some(own) = non_empty(roaster.catalogue_roaster_id.as_ref()) {
        return Some(own.clone());
    }
    beans
        .iter()
        .filter(|b| b.roaster_id.as_deref() == Some(roaster.id.as_str()) && b.deleted_at.is_none())
        .find_map(|b| non_empty(b.canonical_roaster_id.as_ref()).cloned())
}

// ── Pull merge ────────────────────────────────────────────────────────────
//
// Visualizer's LIST endpoints are thin: `GET /coffee_bags` rows carry only
// `id`, `name`, `roaster_id`, `canonical_coffee_bag_id`; `GET /roasters` rows
// only `id` and `name`. A pull therefore merges by KEY PRESENCE in the raw
// remote JSON: an absent key never touches the local value, a key present as
// `null` clears it. Crema-only fields are never touched by a pull at all.

/// A key in a remote JSON object: `None` = absent, `Some(None)` = present as
/// `null` (or a non-string), `Some(Some(s))` = present with a string.
fn field<'a>(obj: &'a Map<String, Value>, key: &str) -> Option<Option<&'a str>> {
    obj.get(key).map(Value::as_str)
}

/// Apply an optional string key onto `target` when present.
fn take_opt(obj: &Map<String, Value>, key: &str, target: &mut Option<String>) {
    if let Some(v) = field(obj, key) {
        *target = v.map(str::to_owned);
    }
}

/// Apply a string key onto a required-string `target` when present (`null` → "").
fn take_str(obj: &Map<String, Value>, key: &str, target: &mut String) {
    if let Some(v) = field(obj, key) {
        *target = v.unwrap_or_default().to_owned();
    }
}

/// Fold a pulled Visualizer bag (the RAW summary or detail JSON) into its
/// local row. Only keys the payload carries overwrite local values; an absent
/// key leaves the local value alone, a present `null` clears it.
///
/// Remote-owned (Visualizer-modelled) keys: `name`, `roaster_id` (as
/// `local_roaster_id`, the shell's resolution of the remote id — an unknown
/// remote roaster keeps the local link), `canonical_coffee_bag_id`,
/// `roast_date`, `roast_level`, the origin keys, `quality_score`,
/// `tasting_notes`, `place_of_purchase`, `url`, `notes` (HTML → plain) and the
/// user's visible `metadata`. Everything else — bag size, grams left, cost,
/// tags, photo, linked profile, roast type, mix, decaf, rating, favourite,
/// grinder, frozen / defrosted / opened dates, archived state, the catalogue
/// roaster pick, ids and timestamps — is Crema's and is never touched.
#[must_use]
pub fn merge_pulled_bag(local: &Bean, remote: &Value, local_roaster_id: Option<&str>) -> Bean {
    let mut out = local.clone();
    let Some(obj) = remote.as_object() else {
        return out;
    };
    if let Some(Some(id)) = field(obj, "id") {
        out.visualizer_id = Some(id.to_owned());
    }
    if let Some(Some(name)) = field(obj, "name")
        && !name.is_empty()
    {
        out.name = name.to_owned();
    }
    match field(obj, "roaster_id") {
        Some(None) => out.roaster_id = None,
        Some(Some(_)) => {
            if let Some(local_id) = local_roaster_id {
                out.roaster_id = Some(local_id.to_owned());
            }
        }
        None => {}
    }
    take_opt(
        obj,
        "canonical_coffee_bag_id",
        &mut out.canonical_coffee_bag_id,
    );
    take_opt(obj, "roast_date", &mut out.roasted_on);
    if let Some(level) = field(obj, "roast_level") {
        out.roast_level = crate::visualizer_wire::roast_level_from_wire(level);
    }
    take_opt(obj, "country", &mut out.origin.country);
    take_opt(obj, "region", &mut out.origin.region);
    take_opt(obj, "farm", &mut out.origin.farm);
    take_opt(obj, "farmer", &mut out.origin.farmer);
    take_opt(obj, "variety", &mut out.origin.variety);
    take_opt(obj, "elevation", &mut out.origin.elevation);
    take_opt(obj, "processing", &mut out.origin.processing);
    take_opt(obj, "harvest_time", &mut out.origin.harvest_time);
    take_str(obj, "quality_score", &mut out.quality_score);
    take_str(obj, "tasting_notes", &mut out.tasting_notes);
    take_opt(obj, "place_of_purchase", &mut out.place_of_purchase);
    take_opt(obj, "url", &mut out.url);
    if let Some(notes) = field(obj, "notes") {
        out.notes = notes
            .map(crate::visualizer_wire::html_to_plain)
            .unwrap_or_default();
    }
    if let Some(meta) = obj.get("metadata") {
        // The visible blob only; the `crema` block (Crema-only fields) is ignored.
        let mut visible = match meta {
            Value::Object(m) => m.clone(),
            _ => Map::new(),
        };
        visible.remove("crema");
        out.metadata = Value::Object(visible);
    }
    out
}

/// Fold a reconciled remote roaster (the RAW summary or detail JSON) into its
/// local row, by key presence like [`merge_pulled_bag`].
///
/// - `refresh = true` (a [`crate::RoasterReconcileAction::Update`]: the local
///   is already bound) → take the remote `name` / `website` / `image_url` when
///   present; a non-empty remote catalogue link wins, none keeps the local one.
///   Unless the local was **edited since the last sync** (`updated_at >
///   last_sync_at`): then its fields are kept so the push leg sends the edit.
/// - `refresh = false` (a `Bind`) → only the binding, plus the remote's
///   catalogue link when the local has none.
///
/// `updated_at` is never touched (a pull is not a local edit), nor are the
/// Crema-only `city` / `country` / `notes` or the local duplicate-of pointer.
#[must_use]
pub fn merge_pulled_roaster(
    local: &Roaster,
    remote: &Value,
    refresh: bool,
    last_sync_at: Option<i64>,
) -> Roaster {
    let mut out = local.clone();
    let Some(obj) = remote.as_object() else {
        return out;
    };
    if let Some(Some(id)) = field(obj, "id") {
        out.visualizer_id = Some(id.to_owned());
    }
    let link = field(obj, "canonical_roaster_id")
        .flatten()
        .filter(|l| !l.is_empty());
    let edited_here = last_sync_at.is_some_and(|ls| local.updated_at > ls);
    if refresh && !edited_here {
        if let Some(Some(name)) = field(obj, "name")
            && !name.is_empty()
        {
            out.name = name.to_owned();
        }
        take_opt(obj, "website", &mut out.website);
        take_opt(obj, "image_url", &mut out.image_url);
        if let Some(l) = link {
            out.catalogue_roaster_id = Some(l.to_owned());
        }
    } else if let Some(l) = link
        && non_empty(local.catalogue_roaster_id.as_ref()).is_none()
    {
        out.catalogue_roaster_id = Some(l.to_owned());
    }
    out
}

/// Whether a pulled roaster row is KNOWN to have no catalogue link: the key
/// is present and empty / `null`. A thin list row (key absent) says nothing,
/// so the catalogue link-PATCH leg must not fire for it.
#[must_use]
pub fn remote_roaster_unlinked(remote: &Value) -> bool {
    remote
        .as_object()
        .and_then(|o| field(o, "canonical_roaster_id"))
        .is_some_and(|v| v.is_none_or(str::is_empty))
}

/// The remote ids in a pull with no locally-bound row — the ones whose full
/// `GET /…/{id}` detail the shell fetches (list rows are thin). Bound rows
/// merge the summary only: there's no remote edit time to tell when a
/// website-side edit would justify a refetch.
#[must_use]
pub fn remote_ids_needing_detail(bound_ids: &[Option<String>], remote: &[Value]) -> Vec<String> {
    let bound: HashSet<&str> = bound_ids.iter().flatten().map(String::as_str).collect();
    remote
        .iter()
        .filter_map(|r| r.get("id").and_then(Value::as_str))
        .filter(|id| !bound.contains(id))
        .map(str::to_owned)
        .collect()
}

// ── Direction gating ──────────────────────────────────────────────────────

/// Which legs a bean / roaster sync runs, from the two direction settings
/// (`"off" | "backup" | "pull" | "two-way"`): `backup` pushes only, `pull`
/// pulls only (never writes remote), `two-way` does both, `off` (or anything
/// unknown) neither. The Premium gate on writes is separate (a free account
/// downshifts the push legs at run time).
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BeanSyncScope {
    /// Apply remote bags locally.
    pub pull_beans: bool,
    /// Write local bags to Visualizer.
    pub push_beans: bool,
    /// Apply remote roasters locally.
    pub pull_roasters: bool,
    /// Write local roasters to Visualizer.
    pub push_roasters: bool,
}

/// Whether a sync direction pulls (`pull` / `two-way`).
#[must_use]
pub fn sync_direction_pulls(direction: &str) -> bool {
    matches!(direction, "pull" | "two-way")
}

/// Whether a sync direction pushes (`backup` / `two-way`).
#[must_use]
pub fn sync_direction_pushes(direction: &str) -> bool {
    matches!(direction, "backup" | "two-way")
}

/// The legs to run for the beans and roasters directions.
#[must_use]
pub fn bean_sync_scope(beans_direction: &str, roasters_direction: &str) -> BeanSyncScope {
    BeanSyncScope {
        pull_beans: sync_direction_pulls(beans_direction),
        push_beans: sync_direction_pushes(beans_direction),
        pull_roasters: sync_direction_pulls(roasters_direction),
        push_roasters: sync_direction_pushes(roasters_direction),
    }
}

// ── Push planning ─────────────────────────────────────────────────────────

/// One local bag the push leg writes.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BeanPushItem {
    /// The local bean id.
    pub local_id: String,
    /// `true` = never pushed (no `visualizer_id`) → `POST`; `false` = bound
    /// and edited since the last sync → `PATCH`.
    pub create: bool,
}

/// The bean push leg's work list, in library order: every bag with no
/// `visualizer_id` (create), plus every bound bag whose `updated_at` is newer
/// than `last_sync_at` (update; `None` = never synced = 0). Deleted rows and
/// `skip_ids` (rows this run just applied from the pull — they already match
/// the remote) are skipped. Mirrors step 4 of the web `runSync`.
#[must_use]
pub fn plan_bean_push(
    beans: &[Bean],
    last_sync_at: Option<i64>,
    skip_ids: &[String],
) -> Vec<BeanPushItem> {
    let last = last_sync_at.unwrap_or(0);
    let skip: HashSet<&str> = skip_ids.iter().map(String::as_str).collect();
    beans
        .iter()
        .filter(|b| b.deleted_at.is_none() && !skip.contains(b.id.as_str()))
        .filter_map(|b| {
            let create = b.visualizer_id.is_none();
            (create || b.updated_at > last).then(|| BeanPushItem {
                local_id: b.id.clone(),
                create,
            })
        })
        .collect()
}

/// One local roaster the push leg writes.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RoasterPushItem {
    /// The local roaster id.
    pub local_id: String,
    /// `true` = never pushed → `POST`; `false` = bound and edited since the
    /// last sync → `PATCH`.
    pub create: bool,
}

/// The roaster push leg's work list — the same rule as [`plan_bean_push`]:
/// unbound → create, bound and edited since `last_sync_at` → update, minus
/// deleted rows and `skip_ids` (rows this run just pulled).
#[must_use]
pub fn plan_roaster_push(
    roasters: &[Roaster],
    last_sync_at: Option<i64>,
    skip_ids: &[String],
) -> Vec<RoasterPushItem> {
    let last = last_sync_at.unwrap_or(0);
    let skip: HashSet<&str> = skip_ids.iter().map(String::as_str).collect();
    roasters
        .iter()
        .filter(|r| r.deleted_at.is_none() && !skip.contains(r.id.as_str()))
        .filter_map(|r| {
            let create = r.visualizer_id.is_none();
            (create || r.updated_at > last).then(|| RoasterPushItem {
                local_id: r.id.clone(),
                create,
            })
        })
        .collect()
}

/// A bound roaster whose remote row lacks the catalogue link Crema knows.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RoasterLinkPatch {
    /// The local roaster id.
    pub local_id: String,
    /// The catalogue roaster id to send (`canonical_roaster_id`).
    pub catalogue_roaster_id: String,
}

/// The catalogue link-PATCH leg: every bound roaster whose remote row came
/// back with no `canonical_roaster_id` (`unlinked_remote_ids`, Visualizer
/// ids) but for which [`resolve_roaster_catalogue_link`] finds one. Mirrors
/// step 2b of the web `runSync`.
#[must_use]
pub fn plan_roaster_link_patches(
    roasters: &[Roaster],
    beans: &[Bean],
    unlinked_remote_ids: &[String],
) -> Vec<RoasterLinkPatch> {
    let unlinked: HashSet<&str> = unlinked_remote_ids.iter().map(String::as_str).collect();
    roasters
        .iter()
        .filter(|r| {
            r.visualizer_id
                .as_deref()
                .is_some_and(|id| unlinked.contains(id))
        })
        .filter_map(|r| {
            resolve_roaster_catalogue_link(r, beans).map(|link| RoasterLinkPatch {
                local_id: r.id.clone(),
                catalogue_roaster_id: link,
            })
        })
        .collect()
}

// ── Roaster duplicates ────────────────────────────────────────────────────

/// A probable duplicate pair in the roaster directory: `dupe_id` looks like
/// `canonical_id` (same normalised name).
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RoasterDuplicate {
    /// The row to keep — the most recently updated of the group.
    pub canonical_id: String,
    /// The row to fold into it.
    pub dupe_id: String,
}

/// Merge suggestions for the Roasters tab. Rows already tagged as a duplicate
/// (`canonical_roaster_id` set) and deleted rows are skipped; the rest are
/// grouped by name (trimmed, case-insensitive; blank names never match). In
/// each group of two or more, the most recently updated row is the canonical
/// one (ties keep directory order) and every other row is a suggested dupe.
/// Groups come out in the order their first row appears in the directory.
/// Mirrors the web Roasters tab's `dupes`.
#[must_use]
pub fn detect_roaster_duplicates(roasters: &[Roaster]) -> Vec<RoasterDuplicate> {
    let mut order: Vec<String> = Vec::new();
    let mut groups: HashMap<String, Vec<&Roaster>> = HashMap::new();
    for r in roasters {
        if r.canonical_roaster_id.is_some() || r.deleted_at.is_some() {
            continue;
        }
        let key = r.name.trim().to_lowercase();
        if key.is_empty() {
            continue;
        }
        let group = groups.entry(key.clone()).or_default();
        if group.is_empty() {
            order.push(key);
        }
        group.push(r);
    }
    let mut out = Vec::new();
    for key in order {
        let mut group = groups.remove(&key).unwrap_or_default();
        if group.len() < 2 {
            continue;
        }
        // Stable sort: equal timestamps keep directory order.
        group.sort_by_key(|r| std::cmp::Reverse(r.updated_at));
        let canonical = group[0];
        for dupe in &group[1..] {
            out.push(RoasterDuplicate {
                canonical_id: canonical.id.clone(),
                dupe_id: dupe.id.clone(),
            });
        }
    }
    out
}

/// What merging `dupe_id` into `canonical_id` does: re-point `bean_ids` at
/// the canonical roaster, then tag the dupe (`canonical_roaster_id =
/// canonical_id`). The dupe row is kept, so the merge can be undone by
/// clearing that pointer (un-merge); the bags stay on the canonical roaster.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RoasterMergePlan {
    /// The roaster that keeps the bags.
    pub canonical_id: String,
    /// The roaster tagged as a duplicate.
    pub dupe_id: String,
    /// The bags filed under the dupe, in library order.
    pub bean_ids: Vec<String>,
}

/// Plan a roaster merge, or `None` when it can't be done: the two ids are
/// the same, either roaster is missing, or the canonical row is itself
/// tagged as a duplicate (merging into it would chain pointers).
#[must_use]
pub fn plan_roaster_merge(
    roasters: &[Roaster],
    beans: &[Bean],
    canonical_id: &str,
    dupe_id: &str,
) -> Option<RoasterMergePlan> {
    if canonical_id == dupe_id {
        return None;
    }
    let canonical = roasters.iter().find(|r| r.id == canonical_id)?;
    roasters.iter().find(|r| r.id == dupe_id)?;
    if canonical.canonical_roaster_id.is_some() {
        return None;
    }
    Some(RoasterMergePlan {
        canonical_id: canonical_id.to_owned(),
        dupe_id: dupe_id.to_owned(),
        bean_ids: beans
            .iter()
            .filter(|b| b.roaster_id.as_deref() == Some(dupe_id))
            .map(|b| b.id.clone())
            .collect(),
    })
}

// ── Roaster delete ────────────────────────────────────────────────────────

/// What deleting a roaster does (web `RoasterDeleteSplit` + the store's
/// `deleteRoaster` / `deleteRoasterAndBeans`). A **detach** keeps the linked
/// bags and clears their roaster; a **cascade** deletes them too. The remote
/// ids are what an "also delete on Visualizer" sends: every deleted bag's
/// Visualizer id (bags first, then the roaster — the web order).
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RoasterDeletePlan {
    /// The roaster being deleted.
    pub roaster_id: String,
    /// Bags deleted with it (cascade only), in library order.
    pub deleted_bean_ids: Vec<String>,
    /// Bags kept but detached (`roaster_id` cleared) — detach only.
    pub detached_bean_ids: Vec<String>,
    /// Visualizer ids of the deleted bags that were synced.
    pub remote_bean_ids: Vec<String>,
    /// The roaster's own Visualizer id, if it was synced.
    pub remote_roaster_id: Option<String>,
}

/// Plan a roaster delete, or `None` when the roaster isn't in `roasters`.
/// `cascade` = also delete every bag filed under it.
#[must_use]
pub fn plan_roaster_delete(
    roasters: &[Roaster],
    beans: &[Bean],
    roaster_id: &str,
    cascade: bool,
) -> Option<RoasterDeletePlan> {
    let roaster = roasters.iter().find(|r| r.id == roaster_id)?;
    let linked: Vec<&Bean> = beans
        .iter()
        .filter(|b| b.roaster_id.as_deref() == Some(roaster_id))
        .collect();
    let ids = |v: &[&Bean]| v.iter().map(|b| b.id.clone()).collect::<Vec<_>>();
    let (deleted, detached) = if cascade {
        (ids(&linked), Vec::new())
    } else {
        (Vec::new(), ids(&linked))
    };
    let remote_bean_ids = if cascade {
        linked
            .iter()
            .filter_map(|b| non_empty(b.visualizer_id.as_ref()).cloned())
            .collect()
    } else {
        Vec::new()
    };
    Some(RoasterDeletePlan {
        roaster_id: roaster_id.to_owned(),
        deleted_bean_ids: deleted,
        detached_bean_ids: detached,
        remote_bean_ids,
        remote_roaster_id: non_empty(roaster.visualizer_id.as_ref()).cloned(),
    })
}

// ── JSON facades (wasm + UniFFI) ──────────────────────────────────────────

fn parse<T: for<'de> Deserialize<'de>>(s: &str) -> Result<T, String> {
    serde_json::from_str(s).map_err(|e| e.to_string())
}

fn emit<T: Serialize>(v: &T) -> Result<String, String> {
    serde_json::to_string(v).map_err(|e| e.to_string())
}

/// JSON-bridged [`coffee_bag_write_request`]. Input: a `Bean` JSON + the
/// roaster's remote id. Output: the `{"coffee_bag": {...}}` JSON.
///
/// # Errors
/// The JSON error string on a malformed `bean_json`.
pub fn coffee_bag_write_request_json(
    bean_json: &str,
    roaster_remote_id: Option<&str>,
) -> Result<String, String> {
    let bean: Bean = parse(bean_json)?;
    emit(&coffee_bag_write_request(&bean, roaster_remote_id))
}

/// JSON-bridged [`roaster_write_request`]. Input: a `Roaster` JSON. Output:
/// the `{"roaster": {...}}` JSON.
///
/// # Errors
/// The JSON error string on a malformed `roaster_json`.
pub fn roaster_write_request_json(roaster_json: &str) -> Result<String, String> {
    let roaster: Roaster = parse(roaster_json)?;
    emit(&roaster_write_request(&roaster))
}

/// JSON-bridged [`resolve_roaster_catalogue_link`]. Input:
/// `{"roaster": Roaster, "beans": Bean[]}`. Output: the link, or `None`.
///
/// # Errors
/// The JSON error string on malformed input.
pub fn resolve_roaster_catalogue_link_json(payload: &str) -> Result<Option<String>, String> {
    #[derive(Deserialize)]
    struct In {
        roaster: Roaster,
        #[serde(default)]
        beans: Vec<Bean>,
    }
    let inp: In = parse(payload)?;
    Ok(resolve_roaster_catalogue_link(&inp.roaster, &inp.beans))
}

/// JSON-bridged [`merge_pulled_roaster`]. Input: a `Roaster` JSON + a
/// `RoasterWire` JSON + the last-sync baseline. Output: the merged `Roaster`.
///
/// # Errors
/// The JSON error string on malformed input.
pub fn merge_pulled_roaster_json(
    local_json: &str,
    remote_json: &str,
    refresh: bool,
    last_sync_at: Option<i64>,
) -> Result<String, String> {
    let local: Roaster = parse(local_json)?;
    let remote: Value = parse(remote_json)?;
    emit(&merge_pulled_roaster(
        &local,
        &remote,
        refresh,
        last_sync_at,
    ))
}

/// JSON-bridged [`remote_ids_needing_detail`] for bags or roasters. Input:
/// `{"local": (Bean | Roaster)[], "remote": <list rows>[]}` (only each local's
/// `visualizerId` is read). Output: a `string[]` JSON.
///
/// # Errors
/// The JSON error string on malformed input.
pub fn remote_ids_needing_detail_json(payload: &str) -> Result<String, String> {
    #[derive(Deserialize)]
    struct In {
        #[serde(default)]
        local: Vec<Value>,
        #[serde(default)]
        remote: Vec<Value>,
    }
    let inp: In = parse(payload)?;
    let bound: Vec<Option<String>> = inp
        .local
        .iter()
        .map(|l| {
            l.get("visualizerId")
                .and_then(Value::as_str)
                .map(str::to_owned)
        })
        .collect();
    emit(&remote_ids_needing_detail(&bound, &inp.remote))
}

/// JSON-bridged [`remote_roaster_unlinked`].
///
/// # Errors
/// The JSON error string on a malformed `remote_json`.
pub fn remote_roaster_unlinked_json(remote_json: &str) -> Result<bool, String> {
    let remote: Value = parse(remote_json)?;
    Ok(remote_roaster_unlinked(&remote))
}

/// JSON-bridged [`bean_sync_scope`]: the two direction strings → a
/// `BeanSyncScope` JSON.
///
/// # Errors
/// Never in practice (serialising a plain struct).
pub fn bean_sync_scope_json(
    beans_direction: &str,
    roasters_direction: &str,
) -> Result<String, String> {
    emit(&bean_sync_scope(beans_direction, roasters_direction))
}

/// JSON-bridged [`plan_roaster_push`]. Input:
/// `{"roasters": Roaster[], "lastSyncAt"?: number, "skipIds"?: string[]}`.
/// Output: a `RoasterPushItem[]` JSON.
///
/// # Errors
/// The JSON error string on malformed input.
pub fn plan_roaster_push_json(payload: &str) -> Result<String, String> {
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct In {
        roasters: Vec<Roaster>,
        #[serde(default)]
        last_sync_at: Option<i64>,
        #[serde(default)]
        skip_ids: Vec<String>,
    }
    let inp: In = parse(payload)?;
    emit(&plan_roaster_push(
        &inp.roasters,
        inp.last_sync_at,
        &inp.skip_ids,
    ))
}

/// JSON-bridged [`plan_bean_push`]. Input:
/// `{"beans": Bean[], "lastSyncAt"?: number, "skipIds"?: string[]}`. Output: a
/// `BeanPushItem[]` JSON.
///
/// # Errors
/// The JSON error string on malformed input.
pub fn plan_bean_push_json(payload: &str) -> Result<String, String> {
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct In {
        beans: Vec<Bean>,
        #[serde(default)]
        last_sync_at: Option<i64>,
        #[serde(default)]
        skip_ids: Vec<String>,
    }
    let inp: In = parse(payload)?;
    emit(&plan_bean_push(&inp.beans, inp.last_sync_at, &inp.skip_ids))
}

/// JSON-bridged [`plan_roaster_link_patches`]. Input:
/// `{"roasters": Roaster[], "beans": Bean[], "unlinkedRemoteIds": string[]}`.
/// Output: a `RoasterLinkPatch[]` JSON.
///
/// # Errors
/// The JSON error string on malformed input.
pub fn plan_roaster_link_patches_json(payload: &str) -> Result<String, String> {
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct In {
        roasters: Vec<Roaster>,
        #[serde(default)]
        beans: Vec<Bean>,
        #[serde(default)]
        unlinked_remote_ids: Vec<String>,
    }
    let inp: In = parse(payload)?;
    emit(&plan_roaster_link_patches(
        &inp.roasters,
        &inp.beans,
        &inp.unlinked_remote_ids,
    ))
}

/// JSON-bridged [`detect_roaster_duplicates`]. Input: a `Roaster[]` JSON.
/// Output: a `RoasterDuplicate[]` JSON.
///
/// # Errors
/// The JSON error string on a malformed `roasters_json`.
pub fn detect_roaster_duplicates_json(roasters_json: &str) -> Result<String, String> {
    let roasters: Vec<Roaster> = parse(roasters_json)?;
    emit(&detect_roaster_duplicates(&roasters))
}

/// JSON-bridged [`plan_roaster_merge`]. Input:
/// `{"roasters": Roaster[], "beans": Bean[], "canonicalId", "dupeId"}`.
/// Output: a `RoasterMergePlan` JSON, or `null` when the merge can't be done.
///
/// # Errors
/// The JSON error string on malformed input.
pub fn plan_roaster_merge_json(payload: &str) -> Result<String, String> {
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct In {
        roasters: Vec<Roaster>,
        #[serde(default)]
        beans: Vec<Bean>,
        canonical_id: String,
        dupe_id: String,
    }
    let inp: In = parse(payload)?;
    emit(&plan_roaster_merge(
        &inp.roasters,
        &inp.beans,
        &inp.canonical_id,
        &inp.dupe_id,
    ))
}

/// JSON-bridged [`plan_roaster_delete`]. Input:
/// `{"roasters": Roaster[], "beans": Bean[], "roasterId", "cascade"}`.
/// Output: a `RoasterDeletePlan` JSON, or `null` when the roaster is unknown.
///
/// # Errors
/// The JSON error string on malformed input.
pub fn plan_roaster_delete_json(payload: &str) -> Result<String, String> {
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct In {
        roasters: Vec<Roaster>,
        #[serde(default)]
        beans: Vec<Bean>,
        roaster_id: String,
        #[serde(default)]
        cascade: bool,
    }
    let inp: In = parse(payload)?;
    emit(&plan_roaster_delete(
        &inp.roasters,
        &inp.beans,
        &inp.roaster_id,
        inp.cascade,
    ))
}

// ── Tests ─────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn wv(w: &RoasterWire) -> Value {
        serde_json::to_value(w).unwrap()
    }

    fn roaster(id: &str, name: &str, updated_at: i64) -> Roaster {
        let mut r = Roaster::new(id.into(), name.into(), 0);
        r.updated_at = updated_at;
        r
    }

    fn bean(id: &str, roaster_id: Option<&str>) -> Bean {
        let mut b = Bean::new(id.into(), "Gesha".into(), 0);
        b.roaster_id = roaster_id.map(str::to_owned);
        b
    }

    // ── write envelopes ───────────────────────────────────────────────

    #[test]
    fn bag_request_wraps_the_envelope_and_omits_an_empty_catalogue_link() {
        let mut b = bean("bean:1", Some("roaster:1"));
        b.roasted_on = Some("2026-09-01".into());
        let req = coffee_bag_write_request(&b, Some("vz-r1"));
        let body = &req["coffee_bag"];
        assert_eq!(body["name"], "Gesha");
        assert_eq!(body["roaster_id"], "vz-r1");
        assert_eq!(body["roast_date"], "2026-09-01");
        assert!(body["frozen_date"].is_null());
        assert!(body.get("canonical_coffee_bag_id").is_none());
        assert!(body.get("id").is_none());
        assert!(body.get("archived_at").is_none());
        assert_eq!(body["metadata"]["crema"]["crema_id"], "bean:1");

        b.canonical_coffee_bag_id = Some(String::new());
        let req = coffee_bag_write_request(&b, None);
        assert!(req["coffee_bag"].get("canonical_coffee_bag_id").is_none());
        assert!(req["coffee_bag"]["roaster_id"].is_null());

        b.canonical_coffee_bag_id = Some("cat-bag-9".into());
        let req = coffee_bag_write_request(&b, None);
        assert_eq!(req["coffee_bag"]["canonical_coffee_bag_id"], "cat-bag-9");
    }

    #[test]
    fn roaster_request_sends_the_catalogue_link_never_the_duplicate_pointer() {
        let mut r = roaster("roaster:2", "Sey", 1);
        r.canonical_roaster_id = Some("roaster:1".into());
        let req = roaster_write_request(&r);
        let body = req["roaster"].as_object().unwrap();
        assert_eq!(body["name"], "Sey");
        assert!(body["website"].is_null());
        assert!(!body.contains_key("canonical_roaster_id"));
        assert_eq!(body.len(), 2);

        r.catalogue_roaster_id = Some("cat-r-7".into());
        let req = roaster_write_request(&r);
        assert_eq!(req["roaster"]["canonical_roaster_id"], "cat-r-7");
        assert!(!req.to_string().contains("roaster:1"));
    }

    #[test]
    fn catalogue_link_prefers_the_roaster_then_a_live_catalogue_bean() {
        let r = roaster("roaster:1", "Sey", 1);
        let mut deleted = bean("bean:a", Some("roaster:1"));
        deleted.canonical_roaster_id = Some("cat-dead".into());
        deleted.deleted_at = Some(5);
        let plain = bean("bean:b", Some("roaster:1"));
        let mut picked = bean("bean:c", Some("roaster:1"));
        picked.canonical_roaster_id = Some("cat-r-1".into());
        let mut other = bean("bean:d", Some("roaster:2"));
        other.canonical_roaster_id = Some("cat-other".into());
        let beans = vec![deleted, plain, other, picked];
        assert_eq!(
            resolve_roaster_catalogue_link(&r, &beans).as_deref(),
            Some("cat-r-1")
        );
        let mut own = r.clone();
        own.catalogue_roaster_id = Some("cat-own".into());
        assert_eq!(
            resolve_roaster_catalogue_link(&own, &beans).as_deref(),
            Some("cat-own")
        );
        assert_eq!(resolve_roaster_catalogue_link(&r, &[]), None);
    }

    // ── pull merge ────────────────────────────────────────────────────

    #[test]
    fn refresh_takes_remote_fields_and_keeps_local_link_and_dup_pointer() {
        let mut local = roaster("roaster:1", "sey", 1);
        local.catalogue_roaster_id = Some("cat-local".into());
        local.canonical_roaster_id = Some("roaster:0".into());
        local.city = Some("Brooklyn".into());
        let wire = RoasterWire {
            id: Some("vz-1".into()),
            name: "Sey Coffee".into(),
            website: Some("https://sey.coffee".into()),
            image_url: None,
            canonical_roaster_id: None,
        };
        let out = merge_pulled_roaster(&local, &wv(&wire), true, Some(1_000));
        assert_eq!(out.name, "Sey Coffee");
        assert_eq!(out.website.as_deref(), Some("https://sey.coffee"));
        assert_eq!(out.visualizer_id.as_deref(), Some("vz-1"));
        assert_eq!(out.catalogue_roaster_id.as_deref(), Some("cat-local"));
        assert_eq!(out.canonical_roaster_id.as_deref(), Some("roaster:0"));
        assert_eq!(out.city.as_deref(), Some("Brooklyn"));
        // A pull never moves the edit stamp.
        assert_eq!(out.updated_at, 1);

        let linked = RoasterWire {
            canonical_roaster_id: Some("cat-remote".into()),
            ..wire
        };
        let out = merge_pulled_roaster(&local, &wv(&linked), true, Some(1_000));
        assert_eq!(out.catalogue_roaster_id.as_deref(), Some("cat-remote"));
    }

    #[test]
    fn bind_only_binds_and_fills_a_missing_link() {
        let local = roaster("roaster:1", "sey", 1);
        let wire = RoasterWire {
            id: Some("vz-1".into()),
            name: "Sey Coffee".into(),
            website: Some("https://sey.coffee".into()),
            image_url: None,
            canonical_roaster_id: Some("cat-remote".into()),
        };
        let out = merge_pulled_roaster(&local, &wv(&wire), false, Some(1_000));
        assert_eq!(out.name, "sey");
        assert_eq!(out.website, None);
        assert_eq!(out.visualizer_id.as_deref(), Some("vz-1"));
        assert_eq!(out.catalogue_roaster_id.as_deref(), Some("cat-remote"));

        let mut linked = local.clone();
        linked.catalogue_roaster_id = Some("cat-local".into());
        let out = merge_pulled_roaster(&linked, &wv(&wire), false, Some(1_000));
        assert_eq!(out.catalogue_roaster_id.as_deref(), Some("cat-local"));
    }

    // ── push planning ─────────────────────────────────────────────────

    #[test]
    fn bean_push_creates_unbound_and_updates_dirty_bound() {
        let fresh = bean("bean:new", None);
        let mut clean = bean("bean:clean", None);
        clean.visualizer_id = Some("vz-c".into());
        clean.updated_at = 100;
        let mut dirty = bean("bean:dirty", None);
        dirty.visualizer_id = Some("vz-d".into());
        dirty.updated_at = 300;
        let mut gone = bean("bean:gone", None);
        gone.deleted_at = Some(1);
        let beans = vec![fresh, clean, dirty, gone];
        let plan = plan_bean_push(&beans, Some(200), &[]);
        assert_eq!(
            plan,
            vec![
                BeanPushItem {
                    local_id: "bean:new".into(),
                    create: true
                },
                BeanPushItem {
                    local_id: "bean:dirty".into(),
                    create: false
                },
            ]
        );
        // Never synced: every bound bag is dirty.
        assert_eq!(plan_bean_push(&beans, None, &[]).len(), 3);
        // Rows this run just pulled are not echoed back.
        assert_eq!(
            plan_bean_push(&beans, None, &["bean:dirty".into()]).len(),
            2
        );
    }

    #[test]
    fn link_patches_cover_only_unlinked_bound_roasters_with_a_known_link() {
        let mut a = roaster("roaster:a", "A", 1);
        a.visualizer_id = Some("vz-a".into());
        a.catalogue_roaster_id = Some("cat-a".into());
        let mut b = roaster("roaster:b", "B", 1);
        b.visualizer_id = Some("vz-b".into());
        let mut c = roaster("roaster:c", "C", 1);
        c.visualizer_id = Some("vz-c".into());
        c.catalogue_roaster_id = Some("cat-c".into());
        let unbound = roaster("roaster:d", "D", 1);
        let mut picked = bean("bean:1", Some("roaster:b"));
        picked.canonical_roaster_id = Some("cat-b".into());
        let plan = plan_roaster_link_patches(
            &[a, b, c, unbound],
            &[picked],
            &["vz-a".into(), "vz-b".into()],
        );
        assert_eq!(
            plan,
            vec![
                RoasterLinkPatch {
                    local_id: "roaster:a".into(),
                    catalogue_roaster_id: "cat-a".into()
                },
                RoasterLinkPatch {
                    local_id: "roaster:b".into(),
                    catalogue_roaster_id: "cat-b".into()
                },
            ]
        );
    }

    #[test]
    fn a_dirty_bound_roaster_keeps_its_edit_through_a_refresh() {
        let mut local = roaster("roaster:1", "Sey (renamed here)", 1);
        local.visualizer_id = Some("vz-1".into());
        local.updated_at = 2_000;
        let wire = RoasterWire {
            id: Some("vz-1".into()),
            name: "Sey".into(),
            website: Some("https://sey.coffee".into()),
            image_url: None,
            canonical_roaster_id: Some("cat-1".into()),
        };
        let kept = merge_pulled_roaster(&local, &wv(&wire), true, Some(1_000));
        assert_eq!(kept.name, "Sey (renamed here)");
        assert_eq!(kept.website, None);
        assert_eq!(kept.catalogue_roaster_id.as_deref(), Some("cat-1"));
        assert_eq!(kept.updated_at, 2_000);
        // Not edited since the baseline → the remote fields win.
        let taken = merge_pulled_roaster(&local, &wv(&wire), true, Some(3_000));
        assert_eq!(taken.name, "Sey");
    }

    #[test]
    fn direction_scope_is_exact_for_every_direction() {
        for (dir, pull, push) in [
            ("off", false, false),
            ("backup", false, true),
            ("pull", true, false),
            ("two-way", true, true),
            ("bogus", false, false),
        ] {
            let s = bean_sync_scope(dir, dir);
            assert_eq!((s.pull_beans, s.push_beans), (pull, push), "{dir}");
            assert_eq!((s.pull_roasters, s.push_roasters), (pull, push), "{dir}");
        }
        let mixed = bean_sync_scope("pull", "two-way");
        assert!(!mixed.push_beans && mixed.push_roasters && mixed.pull_roasters);
    }

    #[test]
    fn roaster_push_mirrors_the_bean_rule() {
        let fresh = roaster("roaster:new", "New", 1);
        let mut clean = roaster("roaster:clean", "Clean", 100);
        clean.visualizer_id = Some("vz-c".into());
        let mut dirty = roaster("roaster:dirty", "Dirty", 300);
        dirty.visualizer_id = Some("vz-d".into());
        let mut gone = roaster("roaster:gone", "Gone", 1);
        gone.deleted_at = Some(1);
        let rs = vec![fresh, clean, dirty, gone];
        let plan = plan_roaster_push(&rs, Some(200), &[]);
        assert_eq!(
            plan,
            vec![
                RoasterPushItem {
                    local_id: "roaster:new".into(),
                    create: true
                },
                RoasterPushItem {
                    local_id: "roaster:dirty".into(),
                    create: false
                },
            ]
        );
        assert_eq!(
            plan_roaster_push(&rs, Some(200), &["roaster:dirty".into()]).len(),
            1
        );
        let j = json!({"roasters": rs, "lastSyncAt": 200}).to_string();
        assert!(
            plan_roaster_push_json(&j)
                .unwrap()
                .contains("roaster:dirty")
        );
    }

    /// The cross-run contract both shells rely on: pull → apply → the next
    /// run's plan writes nothing; a local edit after the pull writes once.
    #[test]
    fn pull_then_sync_writes_nothing_and_a_later_edit_writes_once() {
        use crate::visualizer_sync::{BeanReconcileAction, reconcile_beans};
        let run1_end = 5_000;
        // Run 1 pulls a remote bag + roaster (Add): decoded with "now" stamps.
        let mut pulled = bean("bean:p", None);
        pulled.visualizer_id = Some("vb-1".into());
        pulled.updated_at = 4_000;
        let mut pulled_r = roaster("roaster:p", "Onyx", 4_000);
        pulled_r.visualizer_id = Some("vr-1".into());
        // In run 1 the push leg skips what it just pulled.
        assert!(plan_bean_push(std::slice::from_ref(&pulled), None, &["bean:p".into()]).is_empty());
        assert!(
            plan_roaster_push(std::slice::from_ref(&pulled_r), None, &["roaster:p".into()])
                .is_empty()
        );

        // Run 2 (baseline = run 1's end): the same remote again.
        let mut decoded = pulled.clone();
        decoded.id = "bean:fallback".into();
        decoded.updated_at = 9_000; // decode fallback = run 2's "now"
        let actions = reconcile_beans(
            std::slice::from_ref(&pulled),
            &[decoded.clone()],
            &HashMap::new(),
            Some(run1_end),
        );
        assert!(actions.is_empty(), "{actions:?}");
        let wire = RoasterWire {
            id: Some("vr-1".into()),
            name: "Onyx".into(),
            ..RoasterWire::default()
        };
        let merged = merge_pulled_roaster(&pulled_r, &wv(&wire), true, Some(run1_end));
        assert_eq!(merged, pulled_r);
        assert!(plan_bean_push(std::slice::from_ref(&pulled), Some(run1_end), &[]).is_empty());
        assert!(plan_roaster_push(std::slice::from_ref(&merged), Some(run1_end), &[]).is_empty());

        // A genuine local edit after the pull → kept by the pull, pushed once.
        let mut edited = pulled.clone();
        edited.name = "Renamed".into();
        edited.updated_at = 6_000;
        let actions = reconcile_beans(
            std::slice::from_ref(&edited),
            &[decoded],
            &HashMap::new(),
            Some(run1_end),
        );
        assert!(
            !actions
                .iter()
                .any(|a| matches!(a, BeanReconcileAction::Replace { .. }))
        );
        assert_eq!(plan_bean_push(&[edited], Some(run1_end), &[]).len(), 1);
        let mut edited_r = merged;
        edited_r.name = "Onyx Lab".into();
        edited_r.updated_at = 6_000;
        let kept = merge_pulled_roaster(&edited_r, &wv(&wire), true, Some(run1_end));
        assert_eq!(kept.name, "Onyx Lab");
        assert_eq!(plan_roaster_push(&[kept], Some(run1_end), &[]).len(), 1);
    }

    #[test]
    fn a_thin_roaster_row_never_blanks_the_website_or_logo() {
        let mut local = roaster("roaster:1", "Sey", 1);
        local.visualizer_id = Some("vz-1".into());
        local.website = Some("https://sey.coffee".into());
        local.image_url = Some("https://sey.coffee/logo.png".into());
        local.catalogue_roaster_id = Some("cat-1".into());
        local.city = Some("Brooklyn".into());
        // `GET /roasters` summary: id + name only.
        let row = json!({ "id": "vz-1", "name": "Sey Coffee" });
        let out = merge_pulled_roaster(&local, &row, true, Some(10));
        assert_eq!(out.name, "Sey Coffee");
        assert_eq!(out.website.as_deref(), Some("https://sey.coffee"));
        assert_eq!(
            out.image_url.as_deref(),
            Some("https://sey.coffee/logo.png")
        );
        assert_eq!(out.catalogue_roaster_id.as_deref(), Some("cat-1"));
        assert_eq!(out.city.as_deref(), Some("Brooklyn"));
        // A detail with an explicit null clears just that field.
        let detail = json!({ "id": "vz-1", "name": "Sey Coffee", "website": null });
        assert_eq!(
            merge_pulled_roaster(&local, &detail, true, Some(10)).website,
            None
        );
        // A summary says nothing about the catalogue link; a detail with null does.
        assert!(!remote_roaster_unlinked(&row));
        assert!(remote_roaster_unlinked(
            &json!({ "id": "vz-1", "canonical_roaster_id": null })
        ));
        assert!(!remote_roaster_unlinked(
            &json!({ "id": "vz-1", "canonical_roaster_id": "c" })
        ));
    }

    #[test]
    fn only_rows_new_to_this_device_need_a_detail_fetch() {
        let rows = vec![
            json!({ "id": "vb-1" }),
            json!({ "id": "vb-2" }),
            json!({ "name": "no id" }),
        ];
        let bound = vec![Some("vb-1".to_owned()), None];
        assert_eq!(remote_ids_needing_detail(&bound, &rows), vec!["vb-2"]);
        let payload = json!({ "local": [{ "visualizerId": "vb-2" }], "remote": rows }).to_string();
        assert_eq!(
            remote_ids_needing_detail_json(&payload).unwrap(),
            r#"["vb-1"]"#
        );
    }

    // ── duplicates ────────────────────────────────────────────────────

    #[test]
    fn duplicates_group_by_normalised_name_newest_wins() {
        let roasters = vec![
            roaster("roaster:1", "Sey", 10),
            roaster("roaster:2", "Onyx", 5),
            roaster("roaster:3", "  sey ", 30),
            roaster("roaster:4", "SEY", 20),
            roaster("roaster:5", "", 1),
            roaster("roaster:6", " ", 2),
            roaster("roaster:7", "onyx", 5),
        ];
        let d = detect_roaster_duplicates(&roasters);
        let pairs: Vec<(&str, &str)> = d
            .iter()
            .map(|x| (x.canonical_id.as_str(), x.dupe_id.as_str()))
            .collect();
        assert_eq!(
            pairs,
            vec![
                ("roaster:3", "roaster:4"),
                ("roaster:3", "roaster:1"),
                // Tie on updated_at → directory order.
                ("roaster:2", "roaster:7"),
            ]
        );
    }

    #[test]
    fn already_merged_and_deleted_rows_are_not_suggested() {
        let mut merged = roaster("roaster:2", "Sey", 50);
        merged.canonical_roaster_id = Some("roaster:1".into());
        let mut deleted = roaster("roaster:3", "Sey", 60);
        deleted.deleted_at = Some(1);
        let roasters = vec![roaster("roaster:1", "Sey", 10), merged, deleted];
        assert!(detect_roaster_duplicates(&roasters).is_empty());
    }

    #[test]
    fn merge_plan_moves_the_dupes_bags_and_guards_bad_input() {
        let roasters = vec![
            roaster("roaster:1", "Sey", 10),
            roaster("roaster:2", "sey", 5),
        ];
        let beans = vec![
            bean("bean:a", Some("roaster:2")),
            bean("bean:b", Some("roaster:1")),
            bean("bean:c", Some("roaster:2")),
            bean("bean:d", None),
        ];
        let plan = plan_roaster_merge(&roasters, &beans, "roaster:1", "roaster:2").unwrap();
        assert_eq!(plan.bean_ids, vec!["bean:a", "bean:c"]);
        assert_eq!(plan.canonical_id, "roaster:1");
        assert_eq!(plan.dupe_id, "roaster:2");

        assert!(plan_roaster_merge(&roasters, &beans, "roaster:1", "roaster:1").is_none());
        assert!(plan_roaster_merge(&roasters, &beans, "roaster:1", "roaster:9").is_none());
        let mut chained = roasters.clone();
        chained[0].canonical_roaster_id = Some("roaster:0".into());
        assert!(plan_roaster_merge(&chained, &beans, "roaster:1", "roaster:2").is_none());
    }

    // ── roaster delete ────────────────────────────────────────────────

    #[test]
    fn roaster_delete_detach_keeps_bags_cascade_takes_them_and_their_remote_ids() {
        let mut r = roaster("roaster:1", "Sey", 1);
        r.visualizer_id = Some("vz-r".into());
        let mut synced = bean("bean:a", Some("roaster:1"));
        synced.visualizer_id = Some("vz-a".into());
        let local_only = bean("bean:b", Some("roaster:1"));
        let other = bean("bean:c", Some("roaster:2"));
        let roasters = vec![r, roaster("roaster:2", "Onyx", 1)];
        let beans = vec![synced, local_only, other];

        let detach = plan_roaster_delete(&roasters, &beans, "roaster:1", false).unwrap();
        assert_eq!(detach.detached_bean_ids, vec!["bean:a", "bean:b"]);
        assert!(detach.deleted_bean_ids.is_empty());
        assert!(detach.remote_bean_ids.is_empty());
        assert_eq!(detach.remote_roaster_id.as_deref(), Some("vz-r"));

        let cascade = plan_roaster_delete(&roasters, &beans, "roaster:1", true).unwrap();
        assert_eq!(cascade.deleted_bean_ids, vec!["bean:a", "bean:b"]);
        assert!(cascade.detached_bean_ids.is_empty());
        assert_eq!(cascade.remote_bean_ids, vec!["vz-a"]);

        let unsynced = plan_roaster_delete(&roasters, &beans, "roaster:2", true).unwrap();
        assert_eq!(unsynced.remote_roaster_id, None);
        assert!(plan_roaster_delete(&roasters, &beans, "roaster:9", true).is_none());
        let none = json!({"roasters": [], "roasterId": "x", "cascade": true}).to_string();
        assert_eq!(plan_roaster_delete_json(&none).unwrap(), "null");
    }

    // ── JSON facades ──────────────────────────────────────────────────

    #[test]
    fn json_facades_round_trip_camel_case() {
        let roasters = vec![
            roaster("roaster:1", "Sey", 10),
            roaster("roaster:2", "sey", 5),
        ];
        let rj = serde_json::to_string(&roasters).unwrap();
        let d = detect_roaster_duplicates_json(&rj).unwrap();
        assert_eq!(d, r#"[{"canonicalId":"roaster:1","dupeId":"roaster:2"}]"#);

        let beans = vec![bean("bean:a", Some("roaster:2"))];
        let payload = json!({
            "roasters": roasters,
            "beans": beans,
            "canonicalId": "roaster:1",
            "dupeId": "roaster:2",
        })
        .to_string();
        let plan = plan_roaster_merge_json(&payload).unwrap();
        assert_eq!(
            plan,
            r#"{"canonicalId":"roaster:1","dupeId":"roaster:2","beanIds":["bean:a"]}"#
        );
        let none = json!({"roasters": [], "canonicalId": "a", "dupeId": "b"}).to_string();
        assert_eq!(plan_roaster_merge_json(&none).unwrap(), "null");

        let bj = serde_json::to_string(&beans).unwrap();
        assert_eq!(
            plan_bean_push_json(&json!({"beans": beans}).to_string()).unwrap(),
            r#"[{"localId":"bean:a","create":true}]"#
        );
        let _ = bj;
        assert!(plan_bean_push_json("nope").is_err());
        assert_eq!(
            bean_sync_scope_json("pull", "backup").unwrap(),
            r#"{"pullBeans":true,"pushBeans":false,"pullRoasters":false,"pushRoasters":true}"#
        );
        let bag = coffee_bag_write_request_json(&serde_json::to_string(&beans[0]).unwrap(), None)
            .unwrap();
        assert!(bag.starts_with(r#"{"coffee_bag":"#));
        let link = json!({"roaster": roasters[0], "beans": []}).to_string();
        assert_eq!(resolve_roaster_catalogue_link_json(&link).unwrap(), None);
    }
}

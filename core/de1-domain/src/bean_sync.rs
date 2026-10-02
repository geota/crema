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

use crate::bean::{Bean, Roaster};
use crate::visualizer_wire::{RoasterWire, bean_to_wire};
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

/// Fold a reconciled remote roaster into its local row, stamping
/// `updated_at = now_ms`.
///
/// - `refresh = true` (a [`crate::RoasterReconcileAction::Update`]: the local
///   is already bound) → take the remote `name` / `website` / `image_url`;
///   the remote catalogue link wins, a remote with none keeps the local one.
/// - `refresh = false` (a `Bind`) → only the binding, plus the remote's
///   catalogue link when the local has none.
///
/// Either way the local duplicate-of pointer (`canonical_roaster_id`) is
/// never touched by a pull. A remote without an id leaves `visualizer_id`
/// alone.
#[must_use]
pub fn merge_pulled_roaster(
    local: &Roaster,
    remote: &RoasterWire,
    refresh: bool,
    now_ms: i64,
) -> Roaster {
    let mut out = local.clone();
    if let Some(id) = &remote.id {
        out.visualizer_id = Some(id.clone());
    }
    if refresh {
        out.name.clone_from(&remote.name);
        out.website.clone_from(&remote.website);
        out.image_url.clone_from(&remote.image_url);
        if remote.canonical_roaster_id.is_some() {
            out.catalogue_roaster_id
                .clone_from(&remote.canonical_roaster_id);
        }
    } else if let Some(link) = non_empty(remote.canonical_roaster_id.as_ref())
        && non_empty(local.catalogue_roaster_id.as_ref()).is_none()
    {
        out.catalogue_roaster_id = Some(link.clone());
    }
    out.updated_at = now_ms;
    out
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
/// than `last_sync_at` (update; `None` = never synced = 0). Deleted rows are
/// skipped. Mirrors step 4 of the web `runSync`.
#[must_use]
pub fn plan_bean_push(beans: &[Bean], last_sync_at: Option<i64>) -> Vec<BeanPushItem> {
    let last = last_sync_at.unwrap_or(0);
    beans
        .iter()
        .filter(|b| b.deleted_at.is_none())
        .filter_map(|b| {
            if b.visualizer_id.is_none() {
                Some(BeanPushItem {
                    local_id: b.id.clone(),
                    create: true,
                })
            } else if b.updated_at > last {
                Some(BeanPushItem {
                    local_id: b.id.clone(),
                    create: false,
                })
            } else {
                None
            }
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
/// `RoasterWire` JSON. Output: the merged `Roaster` JSON.
///
/// # Errors
/// The JSON error string on malformed input.
pub fn merge_pulled_roaster_json(
    local_json: &str,
    remote_json: &str,
    refresh: bool,
    now_ms: i64,
) -> Result<String, String> {
    let local: Roaster = parse(local_json)?;
    let remote: RoasterWire = parse(remote_json)?;
    emit(&merge_pulled_roaster(&local, &remote, refresh, now_ms))
}

/// JSON-bridged [`plan_bean_push`]. Input: a `Bean[]` JSON. Output: a
/// `BeanPushItem[]` JSON.
///
/// # Errors
/// The JSON error string on a malformed `beans_json`.
pub fn plan_bean_push_json(beans_json: &str, last_sync_at: Option<i64>) -> Result<String, String> {
    let beans: Vec<Bean> = parse(beans_json)?;
    emit(&plan_bean_push(&beans, last_sync_at))
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

// ── Tests ─────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

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
        let out = merge_pulled_roaster(&local, &wire, true, 99);
        assert_eq!(out.name, "Sey Coffee");
        assert_eq!(out.website.as_deref(), Some("https://sey.coffee"));
        assert_eq!(out.visualizer_id.as_deref(), Some("vz-1"));
        assert_eq!(out.catalogue_roaster_id.as_deref(), Some("cat-local"));
        assert_eq!(out.canonical_roaster_id.as_deref(), Some("roaster:0"));
        assert_eq!(out.city.as_deref(), Some("Brooklyn"));
        assert_eq!(out.updated_at, 99);

        let linked = RoasterWire {
            canonical_roaster_id: Some("cat-remote".into()),
            ..wire
        };
        let out = merge_pulled_roaster(&local, &linked, true, 99);
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
        let out = merge_pulled_roaster(&local, &wire, false, 7);
        assert_eq!(out.name, "sey");
        assert_eq!(out.website, None);
        assert_eq!(out.visualizer_id.as_deref(), Some("vz-1"));
        assert_eq!(out.catalogue_roaster_id.as_deref(), Some("cat-remote"));

        let mut linked = local.clone();
        linked.catalogue_roaster_id = Some("cat-local".into());
        let out = merge_pulled_roaster(&linked, &wire, false, 7);
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
        let plan = plan_bean_push(&beans, Some(200));
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
        assert_eq!(plan_bean_push(&beans, None).len(), 3);
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
            plan_bean_push_json(&bj, None).unwrap(),
            r#"[{"localId":"bean:a","create":true}]"#
        );
        assert!(plan_bean_push_json("nope", None).is_err());
        let bag = coffee_bag_write_request_json(&serde_json::to_string(&beans[0]).unwrap(), None)
            .unwrap();
        assert!(bag.starts_with(r#"{"coffee_bag":"#));
        let link = json!({"roaster": roasters[0], "beans": []}).to_string();
        assert_eq!(resolve_roaster_catalogue_link_json(&link).unwrap(), None);
    }
}

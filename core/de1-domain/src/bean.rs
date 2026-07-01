//! Roast-classification helpers for the **current bean** — the bag of
//! coffee being pulled.
//!
//! Three pure helpers shared by every shell:
//!
//! - [`roast_band`] — classify a 1..10 roast level into a named band
//!   (`Light` / `Medium` / `Dark`), the same band the Visualizer v2
//!   `.shot.json` `bean.roast_level` field encodes.
//! - [`days_off_roast`] — whole days between an ISO `yyyy-mm-dd` roast
//!   date and a "now" timestamp, computed on UTC calendar days so the
//!   readout is stable across the day.
//! - [`roast_freshness`] — rate that day count against the ideal rest
//!   window for its band, the verdict (`Best` / `Ok` / `Bad`) that
//!   drives the bean card's status dot.
//!
//! This module covers the pure classification helpers only. A typed
//! bean shape and the profile-roast inference (`roastFromProfile`) are
//! explicitly deferred until Android starts modelling beans.
//!
//! **2026-05-23 — Bean library types added.** The
//! per-bag canonical types ([`Bean`], [`Roaster`], [`BeanOrigin`],
//! [`BeanMix`], [`ShotBean`]) now live here and are emitted to every
//! shell via `#[typeshare]`. Pure helpers ([`BeanRecord::is_off_roast`],
//! [`BeanRecord::display_summary`], [`Bean::freshness_band`]) ride on
//! the same module so every shell consumes the same classification.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

/// A coffee roast level, categorized into a named band — the same scale
/// Visualizer's v2 `.shot.json` `bean.roast_level` field uses (1..10 with
/// 1 = lightest, 10 = darkest, in three equal-ish bands). Serializes as
/// the lowercase wire spelling (`"light"` / `"medium"` / `"dark"`) to
/// match the shell's `Roast` type alias and the v2 JSON contract.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RoastBand {
    /// Light roast — 1..3 on the Visualizer scale.
    Light,
    /// Medium roast — 4..6 on the Visualizer scale.
    Medium,
    /// Dark roast — 7..10 on the Visualizer scale.
    Dark,
}

impl RoastBand {
    /// The lowercase wire string for this band (`"light"` / `"medium"` /
    /// `"dark"`) — the same spelling the shell's `Roast` type alias uses.
    pub fn as_str(self) -> &'static str {
        match self {
            RoastBand::Light => "light",
            RoastBand::Medium => "medium",
            RoastBand::Dark => "dark",
        }
    }

    /// Parse a lowercase wire string (`"light"` / `"medium"` / `"dark"`) back
    /// into a [`RoastBand`]; `None` for any other spelling. The inverse of
    /// [`as_str`](Self::as_str) — the typed parse the bridges call instead of
    /// re-matching the wire strings inline.
    pub fn from_wire_str(s: &str) -> Option<RoastBand> {
        match s {
            "light" => Some(RoastBand::Light),
            "medium" => Some(RoastBand::Medium),
            "dark" => Some(RoastBand::Dark),
            _ => None,
        }
    }
}

/// A bean's rest verdict against the ideal degas window for its
/// [`RoastBand`]. Serializes as lowercase (`"best"` / `"ok"` / `"bad"`),
/// matching the shell's `Freshness` type alias.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RoastFreshness {
    /// Inside the green window — the best part of the bean's life.
    Best,
    /// Still drinkable but outside the green window — too gassy below it,
    /// fading above it.
    Ok,
    /// Past the rest window — the bean is stale.
    Bad,
}

impl RoastFreshness {
    /// The lowercase wire string for this verdict.
    pub fn as_str(self) -> &'static str {
        match self {
            RoastFreshness::Best => "best",
            RoastFreshness::Ok => "ok",
            RoastFreshness::Bad => "bad",
        }
    }
}

/// Per-band rest windows, in days off roast:
/// - `green` is the `[low, high]` best window;
/// - `ok_high` is the upper bound of the still-drinkable fading window —
///   past it the bean is stale.
///
/// Below the green window the bean also only rates `ok` (still too
/// gassy / unstable). The windows differ because degassing tracks bean
/// density: darker beans are porous and degas fast (earliest, shortest
/// window); light roasts are dense, hold CO₂ longest, and need the most
/// rest.
struct RestWindow {
    green: (i64, i64),
    ok_high: i64,
}

/// Dark — best 4–10, ok 0–3 / 11–14, bad 15+.
const DARK_WINDOW: RestWindow = RestWindow {
    green: (4, 10),
    ok_high: 14,
};
/// Medium — best 6–14, ok 0–5 / 15–21, bad 22+.
const MEDIUM_WINDOW: RestWindow = RestWindow {
    green: (6, 14),
    ok_high: 21,
};
/// Light — best 10–24, ok 0–9 / 25–35, bad 36+.
const LIGHT_WINDOW: RestWindow = RestWindow {
    green: (10, 24),
    ok_high: 35,
};

/// Milliseconds per UTC day. Calendar days are not constant length under
/// DST, but the days-off-roast math is done in UTC, where every day is
/// exactly 86 400 000 ms.
const MS_PER_DAY: i64 = 86_400_000;

/// Classify a 1..10 roast level into a named [`RoastBand`]:
///
/// - `1..=3` → [`RoastBand::Light`]
/// - `4..=6` → [`RoastBand::Medium`]
/// - `7..=10` → [`RoastBand::Dark`]
///
/// Values outside 1..10 are clamped into range first (negative levels
/// read as Light; large levels read as Dark). The 1..10 scale is the
/// Visualizer v2 `bean.roast_level` contract.
pub fn roast_band(level: i32) -> RoastBand {
    let clamped = level.clamp(1, 10);
    if clamped <= 3 {
        RoastBand::Light
    } else if clamped <= 6 {
        RoastBand::Medium
    } else {
        RoastBand::Dark
    }
}

/// Finer 5-bucket **display** label for a 1..10 roast level — the roast
/// slider caption and library tile pill:
///
/// - `1..=2`  → `"Light"`
/// - `3..=4`  → `"Med-light"`
/// - `5`      → `"Medium"`
/// - `6..=7`  → `"Med-dark"`
/// - `8..=10` → `"Dark"`
///
/// Values outside 1..10 are clamped into range first. This is **display
/// only**: every freshness comparison and filter rides on the canonical
/// 3-band [`roast_band`] — this just gives the UI a finer caption. Both
/// shells render these exact strings.
pub fn roast_band5(level: i32) -> &'static str {
    match level.clamp(1, 10) {
        1..=2 => "Light",
        3..=4 => "Med-light",
        5 => "Medium",
        6..=7 => "Med-dark",
        _ => "Dark",
    }
}

#[cfg(test)]
mod roast_band5_tests {
    use super::roast_band5;

    #[test]
    fn maps_every_bucket_and_clamps_out_of_range() {
        // The exact strings both shells used to hand-roll.
        let cases = [
            (1, "Light"),
            (2, "Light"),
            (3, "Med-light"),
            (4, "Med-light"),
            (5, "Medium"),
            (6, "Med-dark"),
            (7, "Med-dark"),
            (8, "Dark"),
            (10, "Dark"),
        ];
        for (level, label) in cases {
            assert_eq!(roast_band5(level), label, "level {level}");
        }
        // Out of range clamps into 1..=10 (≤0 → Light, >10 → Dark).
        assert_eq!(roast_band5(0), "Light");
        assert_eq!(roast_band5(-3), "Light");
        assert_eq!(roast_band5(99), "Dark");
    }
}

/// Whole calendar days (UTC) between an ISO `yyyy-mm-dd` roast date and
/// the `now_unix_ms` reference time — the bean's "days off roast".
///
/// Returns `None` when the date string is malformed (not 10 chars in
/// `yyyy-mm-dd` shape, or non-numeric / out-of-range parts). The result
/// is clamped at `0` so a future-dated roast never reads negative.
///
/// The arithmetic is done on the calendar day only (UTC midnight of
/// each day), so a shot pulled at any time of day reports a stable
/// integer — matching the shell implementation.
///
/// `now_unix_ms` is a parameter rather than a wall-clock read so the
/// core stays sans-IO; the shell passes `Date.now()` at the call site.
pub fn days_off_roast(roasted_on: &str, now_unix_ms: i64) -> Option<i64> {
    let roast_unix_ms = parse_iso_date_to_unix_ms(roasted_on)?;
    // Floor-divide `now_unix_ms` to the UTC midnight of "today" so the
    // result is the integer day count regardless of the time of day
    // `now` is read at. Rust's integer `div_euclid` floors toward
    // negative infinity, matching the JS `Math.floor` semantics.
    let today_unix_ms = now_unix_ms.div_euclid(MS_PER_DAY) * MS_PER_DAY;
    let days = (today_unix_ms - roast_unix_ms).div_euclid(MS_PER_DAY);
    Some(days.max(0))
}

/// Rate how a bean's `days` off roast sits against the ideal rest window
/// for its `band` — the bean card's status dot.
///
/// - [`RoastFreshness::Best`] inside the band's green window;
/// - [`RoastFreshness::Bad`] past the `ok_high` ceiling;
/// - [`RoastFreshness::Ok`] either side in between (still too gassy
///   below the green window, fading above it).
///
/// Days are clamped at 0 (negative day counts cannot reach the green
/// window and read as `Ok`).
pub fn roast_freshness(band: RoastBand, days: i64) -> RoastFreshness {
    let w = match band {
        RoastBand::Light => LIGHT_WINDOW,
        RoastBand::Medium => MEDIUM_WINDOW,
        RoastBand::Dark => DARK_WINDOW,
    };
    if days >= w.green.0 && days <= w.green.1 {
        RoastFreshness::Best
    } else if days > w.ok_high {
        RoastFreshness::Bad
    } else {
        RoastFreshness::Ok
    }
}

/// Parse an ISO `yyyy-mm-dd` date string into Unix-epoch milliseconds at
/// UTC midnight. Returns `None` on a malformed string — anything that
/// is not exactly 10 ASCII characters in `dddd-dd-dd` shape, with parts
/// that are not in calendar range.
///
/// Calendar validation is deliberately coarse (month 1..=12,
/// day-of-month 1..=31) — refusing 2024-02-30 is not the job of this
/// parser; the caller's source data is the bean editor, which already
/// constrains input via a `<input type="date">`. A grossly-invalid
/// string still reports `None` rather than panicking.
fn parse_iso_date_to_unix_ms(s: &str) -> Option<i64> {
    let bytes = s.as_bytes();
    if bytes.len() != 10 || bytes[4] != b'-' || bytes[7] != b'-' {
        return None;
    }
    let year: i32 = s.get(0..4)?.parse().ok()?;
    let month: u32 = s.get(5..7)?.parse().ok()?;
    let day: u32 = s.get(8..10)?.parse().ok()?;
    if !(1..=12).contains(&month) || !(1..=31).contains(&day) {
        return None;
    }
    days_from_civil(year, month, day).map(|d| d * MS_PER_DAY)
}

/// Convert a (year, month, day) UTC date to whole days since the Unix
/// epoch (1970-01-01 UTC), using Howard Hinnant's `days_from_civil`
/// algorithm — branch-free and exact for any Gregorian date in the
/// `i32` year range.
///
/// Returns `None` on overflow (the multiplication / addition stays
/// within `i64` for any plausible roast date, but the helper is
/// defensive in case the caller passes a year far outside the
/// representable range).
fn days_from_civil(y: i32, m: u32, d: u32) -> Option<i64> {
    // Hinnant's algorithm: shift March 1 to be the first day of the
    // "year" so leap-day arithmetic is uniform.
    let y = i64::from(if m <= 2 { y - 1 } else { y });
    let m = i64::from(m);
    let d = i64::from(d);
    let era = if y >= 0 { y } else { y - 399 }.div_euclid(400);
    let yoe = y - era * 400; // [0, 399]
    let doy = (153 * (m + if m > 2 { -3 } else { 9 }) + 2) / 5 + d - 1; // [0, 365]
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy; // [0, 146096]
    // Days since 0000-03-01, then shift the epoch to 1970-01-01.
    era.checked_mul(146_097)?
        .checked_add(doe)?
        .checked_sub(719_468)
}

// ───────────────────────────────────────────────────────────────────
// Bean library — per-bag identity types.
//
// `Bean` is *a bag of coffee* (the unit of stock the user manages).
// `Roaster` is *the roastery* that produced one or more bags. Both are
// owned by the shell's localStorage / IndexedDB / Room store; the core
// just defines the shape (sans-IO) so every shell consumes the same
// thing. A snapshot of the active bean ([`ShotBean`]) is frozen onto
// each completed shot — denormalised on purpose so a later rename does
// not retroactively rewrite history.
// ───────────────────────────────────────────────────────────────────

/// Whether a bag of coffee is a single-origin lot or a blend. `None` =
/// unknown / unset. Serialises as the lowercase wire string
/// (`"single"` / `"blend"`) to match the TS shell's typed string union.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum BeanMix {
    /// A single-origin lot (one farm / region / process).
    Single,
    /// A blend of two or more components.
    Blend,
}

/// Whether a bag is roasted for espresso, filter, or both. `None` on the
/// owning [`Bean`] = unset; the value never serialises as `"unknown"`
/// (the user picks one of the three or leaves it blank). Lowercase wire
/// strings match the TS shell convention.
///
/// Imported from Beanconqueror's `bean_roasting_type` field, which uses
/// the same three categories plus an `Unknown` sentinel that we map to
/// `None` on the bag.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum BeanRoastType {
    /// Roasted for espresso pulls — typically a darker development.
    Espresso,
    /// Roasted for filter / pour-over.
    Filter,
    /// Roasted to work for both espresso and filter (industry term).
    Omni,
}

/// Origin metadata for a bag — country, region, farm, and the rest of
/// the upstream provenance fields. All optional: a fresh bean's origin
/// starts empty and the user fills in whatever the bag label shows.
/// Mirrors Visualizer's CoffeeBag origin fields so the sync mapping is
/// 1:1.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BeanOrigin {
    /// Country of origin — `"Ethiopia"`, `"Ethiopia / Colombia"` for a blend.
    pub country: Option<String>,
    /// Region within the country — `"Yirgacheffe"`, `"Nyeri"`.
    pub region: Option<String>,
    /// Farm name — `"Hambela Estate"`.
    pub farm: Option<String>,
    /// Farmer / producer name — `"Tarekech Geleta"`.
    pub farmer: Option<String>,
    /// Cultivar / variety — `"Geisha"`, `"SL28 / SL34"`.
    pub variety: Option<String>,
    /// Elevation, free text — `"1900-2100 masl"`. Free-form because real
    /// bags inconsistently report m / masl / ft / a range / a single.
    pub elevation: Option<String>,
    /// Process — `"Washed"`, `"Natural"`, `"Anaerobic"`. Free-form for the
    /// same reason as `elevation`.
    pub processing: Option<String>,
    /// Harvest time — `"2024 Spring"`, `"October 2024"`.
    pub harvest_time: Option<String>,
}

/// One bag of coffee — a row in the bean library. This is
/// the central entity: shots reference one by id, and a snapshot
/// ([`ShotBean`]) is frozen onto each completed shot. The core owns the
/// shape so every shell (web, Android, future iOS) consumes the same
/// type via `#[typeshare]`.
///
/// CRUD lives in the shell — the core stays sans-IO and only defines
/// the shape, the conversion helpers and the pure classifiers. A fresh
/// bag is built via [`Bean::new`]; further edits ride on plain field
/// assignment (the shell store is responsible for `updated_at` bumps
/// and persistence).
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Bean {
    /// Stable id — `"bean:<uuid>"`. The shell mints this on create.
    pub id: String,
    /// Bean name — e.g. `"Geisha Esmeralda Lot 3"`. **Required.**
    pub name: String,
    /// FK into the roaster directory. `None` for a bag whose roaster was
    /// not recorded; the shell may opportunistically promote the
    /// roaster name from import to a proper [`Roaster`] row.
    pub roaster_id: Option<String>,
    /// ISO `yyyy-mm-dd` roast date.
    pub roasted_on: Option<String>,
    /// ISO `yyyy-mm-dd` opened-on date. Crema-only — drives the
    /// staleness signal alongside `roasted_on`.
    pub opened_on: Option<String>,
    /// ISO `yyyy-mm-dd` frozen-on date. Drives the freshness math's
    /// freeze-pause: a frozen bag's freshness counter is the days
    /// between `roasted_on` and `frozen_on`, paused while frozen.
    pub frozen_on: Option<String>,
    /// ISO `yyyy-mm-dd` defrosted-on date. Resumes the freshness
    /// counter from `defrosted_on`.
    pub defrosted_on: Option<String>,
    /// Roast level on Visualizer's 1..10 scale. The shell typically
    /// inputs via a 3-band quick-set (light=1, medium=5, dark=10) and
    /// uses [`roast_band`] to bucket back into the band word.
    pub roast_level: Option<u8>,
    /// Single-origin vs blend. `None` = not classified.
    pub mix: Option<BeanMix>,
    /// What the bag was roasted for — espresso, filter, or omni. `None`
    /// when the user hasn't picked. `#[serde(default)]` so older Bean
    /// records (pre-this-field) deserialise cleanly. Imported from
    /// Beanconqueror's `bean_roasting_type` (its `Unknown` value maps
    /// to `None`).
    #[serde(default)]
    pub roast_type: Option<BeanRoastType>,
    /// Decaf flag — `false` by default.
    #[serde(default)]
    pub decaf: bool,
    /// Provenance metadata. Empty struct by default.
    #[serde(default)]
    pub origin: BeanOrigin,
    /// Bag size, grams. `0.0` when unknown. Unit lives in the doc
    /// comment, not the field name, per the locked-in naming rule
    /// (`docs/44-pre-android-handoff.md`).
    #[serde(default)]
    pub bag_size: f32,
    /// Remaining weight in the bag, grams. Auto-debited per shot when
    /// the shell enables `Track bag remaining weight`. `0.0` when
    /// unknown.
    #[serde(default)]
    pub remaining: f32,
    /// Quality score — free text per Visualizer (`"88"`, `"A-"`).
    #[serde(default)]
    pub quality_score: String,
    /// Tasting notes — multi-line free text.
    #[serde(default)]
    pub tasting_notes: String,
    /// User star rating 0..5; `0` = unrated.
    #[serde(default)]
    pub rating: u8,
    /// Where the bag was bought — `"Counter Culture · Durham"`.
    pub place_of_purchase: Option<String>,
    /// What the bag cost — currency-less number in the user's local
    /// units (Crema doesn't track currency yet). `None` = unrecorded.
    /// Imported from Beanconqueror's `cost` field when present.
    #[serde(default)]
    pub cost: Option<f32>,
    /// URL to buy again — Visualizer / roaster / store link.
    pub url: Option<String>,
    /// Free-form notes (not the tasting box).
    #[serde(default)]
    pub notes: String,
    /// Pinned to the brew-page bean picker strip.
    #[serde(default)]
    pub favourite: bool,
    /// Unix epoch ms when the bag was archived; `None` = active.
    #[typeshare(serialized_as = "Option<I64>")]
    pub archived_at: Option<i64>,
    /// Bean-scoped grinder name — `"Niche Zero"`. Bean-scoped because a
    /// grind setting only means something paired with the grinder it
    /// was measured on.
    #[serde(default)]
    pub grinder: String,
    /// Bean-scoped grinder click / setting — `"1.2"`, `"6 + a tooth"`.
    #[serde(default)]
    pub grinder_setting: String,
    /// Free-form user tags — e.g. `"daily-driver"`, `"comp"`, `"experimental"`.
    /// Defaults to an empty list. Serialised as `tags` so the JSON contract
    /// matches the [`crate::Profile`] tag pattern.
    #[serde(default)]
    pub tags: Vec<String>,
    /// The profile auto-loaded when this bean is activated on the Brew
    /// page (the "linked profile"). `None` = no link. Stores the profile
    /// id only; a dangling id (profile deleted, cross-device import of a
    /// device-local custom) is tolerated by every consumer. Local-only —
    /// never pushed to Visualizer. Defaults so older Bean JSON
    /// deserialises cleanly.
    #[serde(default)]
    pub linked_profile_id: Option<String>,
    /// Visualizer `coffee_bag.id` once pushed.
    pub visualizer_id: Option<String>,
    /// Unix epoch ms when this bag was soft-deleted, or `None` when
    /// active. Required for cross-device sync tombstone propagation:
    /// on the next sync push, the remote row is DELETEd and the local
    /// tombstone is garbage-collected. Defaults to `None` so older
    /// `Bean` JSON deserialises cleanly.
    #[serde(default)]
    #[typeshare(serialized_as = "Option<I64>")]
    pub deleted_at: Option<i64>,
    /// Beanconqueror `bean.config.uuid` from a Bc import. Tracks
    /// provenance so a re-import skips beans we already know.
    pub beanconqueror_id: Option<String>,
    /// IndexedDB blob ref for the bag photo, if any.
    pub image_ref: Option<String>,
    /// Open JSON metadata. The escape valve for fields neither
    /// Visualizer nor Crema model first-class, but that an import
    /// (Beanconqueror) or future feature needs to keep round-tripping.
    /// Serialised as `serde_json::Value` so the wire format is a plain
    /// nested object. `serialized_as = "Json"` maps it to a real JSON type in
    /// the generated bindings (Kotlin `JsonElement`, TS `unknown`); without it
    /// typeshare emits a bare, unresolved `Value`.
    #[typeshare(serialized_as = "Json")]
    pub metadata: serde_json::Value,
    /// Unix epoch ms when this bag was created.
    #[typeshare(serialized_as = "I64")]
    pub created_at: i64,
    /// Unix epoch ms when this bag was last updated.
    #[typeshare(serialized_as = "I64")]
    pub updated_at: i64,
}

/// The bag's `remaining` (grams) after a pulled shot debits `dose_g`, floored
/// at 0 — or `None` when there is **nothing to debit**: the bag is already
/// empty (`remaining <= 0`), or the dose / remaining is non-positive or
/// non-finite. A `None` result is the caller's signal to do nothing at all — in
/// particular not to re-persist the bag or touch its `updated_at`.
///
/// The single source of truth for the burn-down + run-out (clamp-to-0) rule
/// *and* its no-op cases; both shells call it through the FFI (`de1_ffi` /
/// `de1_wasm`) so nothing drifts. It is deliberately unaware of bag size — that
/// is a "is this bag tracked?" display concern, and a positive `remaining`
/// already means a tracked, non-empty bag. Detecting the empty transition
/// (`Some(0.0)`) and the unrated-bag prompt stay the shell's job (UI).
#[must_use]
pub fn debit_remaining(remaining: f32, dose_g: f32) -> Option<f32> {
    if !remaining.is_finite() || remaining <= 0.0 || !dose_g.is_finite() || dose_g <= 0.0 {
        return None;
    }
    Some((remaining - dose_g).max(0.0))
}

impl Bean {
    /// Build a brand-new bag with a freshly minted id and `name`. Every
    /// other field starts at its default (empty / `None` / `false` / `0`).
    /// `created_at` and `updated_at` are stamped to `now_unix_ms`.
    pub fn new(id: String, name: String, now_unix_ms: i64) -> Bean {
        Bean {
            id,
            name,
            roaster_id: None,
            roasted_on: None,
            opened_on: None,
            frozen_on: None,
            defrosted_on: None,
            roast_level: None,
            mix: None,
            roast_type: None,
            decaf: false,
            origin: BeanOrigin::default(),
            bag_size: 0.0,
            remaining: 0.0,
            quality_score: String::new(),
            tasting_notes: String::new(),
            rating: 0,
            place_of_purchase: None,
            cost: None,
            url: None,
            notes: String::new(),
            favourite: false,
            archived_at: None,
            grinder: String::new(),
            grinder_setting: String::new(),
            tags: Vec::new(),
            linked_profile_id: None,
            visualizer_id: None,
            deleted_at: None,
            beanconqueror_id: None,
            image_ref: None,
            metadata: serde_json::Value::Null,
            created_at: now_unix_ms,
            updated_at: now_unix_ms,
        }
    }

    /// The bag's [`RoastBand`] if its `roast_level` is set, else `None`.
    /// Bag classification rides on the same `roast_band` helper the
    /// brew-page card uses.
    pub fn freshness_band(&self) -> Option<RoastBand> {
        self.roast_level.map(|n| roast_band(i32::from(n)))
    }

    /// `true` once the bag's rest verdict is [`RoastFreshness::Bad`] —
    /// past its band's stale-out window. `false` while inside the
    /// best / ok window, and `false` (defensively) when the bean has
    /// no `roast_level` or `roasted_on` set so the UI does not flag a
    /// bag the user just hasn't filled in yet.
    pub fn is_off_roast(&self, now_unix_ms: i64) -> bool {
        let Some(band) = self.freshness_band() else {
            return false;
        };
        let Some(roasted_on) = self.roasted_on.as_deref() else {
            return false;
        };
        let Some(days) = days_off_roast(roasted_on, now_unix_ms) else {
            return false;
        };
        matches!(roast_freshness(band, days), RoastFreshness::Bad)
    }

    /// One-line label summarising the bag — `"Ethiopia Yirgacheffe ·
    /// Counter Culture · 14d off roast"`. Slots in the bean-chip on
    /// the brew-page picker and the library card subline. Pieces that
    /// are unset are simply omitted; the dot-separator collapses.
    pub fn display_summary(&self, roaster_name: Option<&str>, now_unix_ms: i64) -> String {
        let mut parts: Vec<String> = Vec::with_capacity(3);
        if !self.name.trim().is_empty() {
            parts.push(self.name.trim().to_owned());
        } else if let Some(country) = self.origin.country.as_deref()
            && !country.trim().is_empty()
        {
            parts.push(country.trim().to_owned());
        }
        if let Some(roaster) = roaster_name
            && !roaster.trim().is_empty()
        {
            parts.push(roaster.trim().to_owned());
        }
        if let Some(roasted_on) = self.roasted_on.as_deref()
            && let Some(days) = days_off_roast(roasted_on, now_unix_ms)
        {
            parts.push(format!("{days}d off roast"));
        }
        parts.join(" · ")
    }
}

/// One roastery — a record in the roaster directory. Sparse on
/// purpose, mirroring Visualizer's `RoasterDetail` (which is itself
/// minimal: id + name + website + image). Beanconqueror has no
/// first-class roaster entity — its `bean.roaster` is free text; the
/// shell promotes unique strings into [`Roaster`] rows on import.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Roaster {
    /// Stable id — `"roaster:<uuid>"`.
    pub id: String,
    /// Roastery name. **Required.**
    pub name: String,
    /// Roastery website / store URL.
    pub website: Option<String>,
    /// Logo / hero image URL. Mirrors Visualizer's `RoasterDetail.image_url`
    /// — round-trips losslessly on sync. Renders as a small thumbnail in
    /// the roaster card and the editor's preview slot.
    #[serde(default)]
    pub image_url: Option<String>,
    /// City — e.g. `"Portland"`. Crema-only; rides in `metadata.crema.city`
    /// on Visualizer round-trip so the wire format stays lossless.
    #[serde(default)]
    pub city: Option<String>,
    /// Country / state / region — free text. Crema-only.
    pub country: Option<String>,
    /// Free-form notes (private to the user — not pushed to Visualizer).
    pub notes: String,
    /// Pointer to the canonical roaster id when this row was tagged as a
    /// duplicate. `None` = this row is itself canonical (or has not been
    /// deduped). Mirrors Visualizer's `RoasterDetail.canonical_roaster_id`
    /// — round-trips directly. Beans pointing at a duplicate are typically
    /// re-pointed at the canonical id on merge.
    #[serde(default)]
    pub canonical_roaster_id: Option<String>,
    /// Visualizer `roaster.id` once pushed.
    pub visualizer_id: Option<String>,
    /// Unix epoch ms when this roaster was soft-deleted, or `None` when
    /// active. See [`Bean::deleted_at`] for the rationale. Defaults to
    /// `None` so older JSON deserialises cleanly.
    #[serde(default)]
    #[typeshare(serialized_as = "Option<I64>")]
    pub deleted_at: Option<i64>,
    /// Open JSON metadata — escape valve symmetric with [`Bean::metadata`].
    #[typeshare(serialized_as = "Json")]
    pub metadata: serde_json::Value,
    /// Unix epoch ms.
    #[typeshare(serialized_as = "I64")]
    pub created_at: i64,
    /// Unix epoch ms.
    #[typeshare(serialized_as = "I64")]
    pub updated_at: i64,
}

impl Roaster {
    /// Build a brand-new roaster row with name + id stamped to `now_unix_ms`.
    pub fn new(id: String, name: String, now_unix_ms: i64) -> Roaster {
        Roaster {
            id,
            name,
            website: None,
            image_url: None,
            city: None,
            country: None,
            notes: String::new(),
            canonical_roaster_id: None,
            visualizer_id: None,
            deleted_at: None,
            metadata: serde_json::Value::Null,
            created_at: now_unix_ms,
            updated_at: now_unix_ms,
        }
    }
}

/// A snapshot of the active bean at the moment a shot was pulled.
/// Frozen onto each [`crate::history::StoredShot`] (in the shell's
/// extended record) so a later rename / archive / delete of the bag
/// does not retroactively rewrite history.
///
/// Beanconqueror reads the bean *live* off the brew (`Brew.bean: uuid`,
/// see `Beanconqueror/src/classes/brew/brew.ts:60`). Crema takes the
/// other approach: snapshot wins. A shot recorded under "Onyx Geisha"
/// stays "Onyx Geisha" forever, even if the user later renames the bag.
///
/// Holds the strict minimum the History list / detail panel needs to
/// render the shot without re-fetching the bag — the user-facing
/// strings plus the dates that drive the freshness pill.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ShotBean {
    /// FK back to the [`Bean`] in the library, if it still exists. The
    /// History row can resolve the link to open the bean detail; an
    /// archived / deleted bean falls back to the snapshot strings.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub bean_id: Option<String>,
    /// Bag name at the time of the shot — `"Geisha Esmeralda Lot 3"`.
    pub name: String,
    /// Roaster name at the time of the shot. Not the roaster id — the
    /// id might dangle, but a string survives.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub roaster_name: Option<String>,
    /// ISO `yyyy-mm-dd` roast date at the time of the shot.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub roasted_on: Option<String>,
    /// Roast level (1..10) at the time of the shot.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub roast_level: Option<u8>,
    /// The bean's tags at the time of the shot.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub tags: Vec<String>,
    /// Per-bean grinder setting at the time of the shot — distinct
    /// from the equipment-level `grinder_model` on [`StoredShot`]
    /// (the *machine* used) and `ShotMetadata::grinder_setting`
    /// (the *dial used for this shot*). This is the bean's own
    /// recommended dial recorded with the bag.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub grinder_setting: Option<String>,
}

impl ShotBean {
    /// Snapshot a [`Bean`] for storage on a shot. The shell passes the
    /// resolved roaster name (looked up in the directory) alongside —
    /// the core stays sans-IO and does not own the roaster table.
    pub fn snapshot_of(bean: &Bean, roaster_name: Option<&str>) -> ShotBean {
        ShotBean {
            bean_id: Some(bean.id.clone()),
            name: bean.name.clone(),
            roaster_name: roaster_name.map(str::to_owned),
            roasted_on: bean.roasted_on.clone(),
            roast_level: bean.roast_level,
            tags: bean.tags.clone(),
            grinder_setting: if bean.grinder_setting.is_empty() {
                None
            } else {
                Some(bean.grinder_setting.clone())
            },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // -- roast_band ---------------------------------------------------

    #[test]
    fn roast_band_classifies_named_thresholds() {
        assert_eq!(roast_band(1), RoastBand::Light);
        assert_eq!(roast_band(3), RoastBand::Light);
        assert_eq!(roast_band(4), RoastBand::Medium);
        assert_eq!(roast_band(5), RoastBand::Medium);
        assert_eq!(roast_band(6), RoastBand::Medium);
        assert_eq!(roast_band(7), RoastBand::Dark);
        assert_eq!(roast_band(10), RoastBand::Dark);
    }

    #[test]
    fn roast_band_clamps_out_of_range() {
        assert_eq!(roast_band(0), RoastBand::Light);
        assert_eq!(roast_band(-5), RoastBand::Light);
        assert_eq!(roast_band(11), RoastBand::Dark);
        assert_eq!(roast_band(999), RoastBand::Dark);
    }

    #[test]
    fn roast_band_serializes_lowercase() {
        assert_eq!(
            serde_json::to_string(&RoastBand::Light).unwrap(),
            "\"light\""
        );
        assert_eq!(
            serde_json::to_string(&RoastBand::Medium).unwrap(),
            "\"medium\""
        );
        assert_eq!(serde_json::to_string(&RoastBand::Dark).unwrap(), "\"dark\"");
    }

    #[test]
    fn roast_band_from_wire_str_round_trips_as_str() {
        for band in [RoastBand::Light, RoastBand::Medium, RoastBand::Dark] {
            assert_eq!(RoastBand::from_wire_str(band.as_str()), Some(band));
        }
        // Unknown / wrong-case spellings reject (the wire is lowercase).
        assert_eq!(RoastBand::from_wire_str("Light"), None);
        assert_eq!(RoastBand::from_wire_str("medium-dark"), None);
        assert_eq!(RoastBand::from_wire_str(""), None);
    }

    // -- days_off_roast -----------------------------------------------

    /// `2026-05-22T12:00:00Z` — a stable reference "now" for the tests.
    const NOW: i64 = 1_779_451_200_000;
    /// `2026-05-22T00:00:00Z` — the UTC-midnight of the reference now.
    const TODAY_MIDNIGHT: i64 = 1_779_408_000_000;

    #[test]
    fn days_off_roast_zero_today() {
        assert_eq!(days_off_roast("2026-05-22", NOW), Some(0));
    }

    #[test]
    fn days_off_roast_handles_time_of_day_consistently() {
        // Same calendar day, any time of day → same answer.
        assert_eq!(days_off_roast("2026-05-22", TODAY_MIDNIGHT), Some(0));
        // 23:59:59 UTC on the same day still reads 0.
        assert_eq!(
            days_off_roast("2026-05-22", TODAY_MIDNIGHT + (86_400_000 - 1)),
            Some(0)
        );
    }

    #[test]
    fn days_off_roast_counts_whole_days() {
        // 7 days earlier reads as 7.
        assert_eq!(days_off_roast("2026-05-15", NOW), Some(7));
        // 1 day earlier reads as 1.
        assert_eq!(days_off_roast("2026-05-21", NOW), Some(1));
    }

    #[test]
    fn days_off_roast_clamps_future_dates_to_zero() {
        // A future roast date never reads negative.
        assert_eq!(days_off_roast("2026-06-01", NOW), Some(0));
        assert_eq!(days_off_roast("2099-01-01", NOW), Some(0));
    }

    #[test]
    fn days_off_roast_crosses_year_boundary() {
        // 2026-05-22 vs 2025-05-22 → 365 days (2025 is not a leap year).
        assert_eq!(days_off_roast("2025-05-22", NOW), Some(365));
        // Across a leap day: 2024-02-28 → 2024-03-01 is 2 days.
        let mar_1_2024 = days_from_civil(2024, 3, 1).unwrap() * MS_PER_DAY;
        assert_eq!(days_off_roast("2024-02-28", mar_1_2024), Some(2));
    }

    #[test]
    fn days_off_roast_rejects_malformed_strings() {
        assert_eq!(days_off_roast("", NOW), None);
        assert_eq!(days_off_roast("not-a-date", NOW), None);
        assert_eq!(days_off_roast("2026/05/22", NOW), None);
        assert_eq!(days_off_roast("2026-5-22", NOW), None); // single-digit month
        assert_eq!(days_off_roast("2026-13-01", NOW), None); // month out of range
        assert_eq!(days_off_roast("2026-05-32", NOW), None); // day out of range
        assert_eq!(days_off_roast("20260522xx", NOW), None); // shape mismatch
    }

    // -- roast_freshness ----------------------------------------------

    #[test]
    fn roast_freshness_inside_green_window_is_best() {
        // Dark: green = [4, 10].
        assert_eq!(roast_freshness(RoastBand::Dark, 4), RoastFreshness::Best);
        assert_eq!(roast_freshness(RoastBand::Dark, 7), RoastFreshness::Best);
        assert_eq!(roast_freshness(RoastBand::Dark, 10), RoastFreshness::Best);
        // Medium: green = [6, 14].
        assert_eq!(roast_freshness(RoastBand::Medium, 6), RoastFreshness::Best);
        assert_eq!(roast_freshness(RoastBand::Medium, 14), RoastFreshness::Best);
        // Light: green = [10, 24].
        assert_eq!(roast_freshness(RoastBand::Light, 10), RoastFreshness::Best);
        assert_eq!(roast_freshness(RoastBand::Light, 24), RoastFreshness::Best);
    }

    #[test]
    fn roast_freshness_below_green_window_is_ok() {
        assert_eq!(roast_freshness(RoastBand::Dark, 0), RoastFreshness::Ok);
        assert_eq!(roast_freshness(RoastBand::Dark, 3), RoastFreshness::Ok);
        assert_eq!(roast_freshness(RoastBand::Medium, 5), RoastFreshness::Ok);
        assert_eq!(roast_freshness(RoastBand::Light, 9), RoastFreshness::Ok);
    }

    #[test]
    fn roast_freshness_above_green_within_ok_is_ok() {
        // Dark: ok_high = 14.
        assert_eq!(roast_freshness(RoastBand::Dark, 11), RoastFreshness::Ok);
        assert_eq!(roast_freshness(RoastBand::Dark, 14), RoastFreshness::Ok);
        // Medium: ok_high = 21.
        assert_eq!(roast_freshness(RoastBand::Medium, 15), RoastFreshness::Ok);
        assert_eq!(roast_freshness(RoastBand::Medium, 21), RoastFreshness::Ok);
        // Light: ok_high = 35.
        assert_eq!(roast_freshness(RoastBand::Light, 25), RoastFreshness::Ok);
        assert_eq!(roast_freshness(RoastBand::Light, 35), RoastFreshness::Ok);
    }

    #[test]
    fn roast_freshness_past_ok_window_is_bad() {
        assert_eq!(roast_freshness(RoastBand::Dark, 15), RoastFreshness::Bad);
        assert_eq!(roast_freshness(RoastBand::Medium, 22), RoastFreshness::Bad);
        assert_eq!(roast_freshness(RoastBand::Light, 36), RoastFreshness::Bad);
        assert_eq!(roast_freshness(RoastBand::Light, 365), RoastFreshness::Bad);
    }

    #[test]
    fn roast_freshness_serializes_lowercase() {
        assert_eq!(
            serde_json::to_string(&RoastFreshness::Best).unwrap(),
            "\"best\""
        );
        assert_eq!(
            serde_json::to_string(&RoastFreshness::Ok).unwrap(),
            "\"ok\""
        );
        assert_eq!(
            serde_json::to_string(&RoastFreshness::Bad).unwrap(),
            "\"bad\""
        );
    }

    // -- days_from_civil sanity --------------------------------------

    #[test]
    fn days_from_civil_matches_known_unix_epoch_dates() {
        // 1970-01-01 → 0 (the Unix epoch).
        assert_eq!(days_from_civil(1970, 1, 1), Some(0));
        // 1970-01-02 → 1.
        assert_eq!(days_from_civil(1970, 1, 2), Some(1));
        // 2000-01-01 → 10957.
        assert_eq!(days_from_civil(2000, 1, 1), Some(10_957));
        // 2024-02-29 (leap day) is valid.
        assert!(days_from_civil(2024, 2, 29).is_some());
    }

    // -- bean library round-trips + helpers ---------------------------

    fn sample_bean() -> Bean {
        let mut bean = Bean::new("bean:abc".to_owned(), "Yirgacheffe".to_owned(), NOW);
        bean.roaster_id = Some("roaster:xyz".to_owned());
        bean.roasted_on = Some("2026-05-08".to_owned());
        bean.roast_level = Some(3);
        bean.mix = Some(BeanMix::Single);
        bean.decaf = false;
        bean.origin = BeanOrigin {
            country: Some("Ethiopia".to_owned()),
            region: Some("Yirgacheffe".to_owned()),
            processing: Some("Washed".to_owned()),
            ..BeanOrigin::default()
        };
        bean.bag_size = 250.0;
        bean.remaining = 142.0;
        bean.tasting_notes = "stone fruit, jasmine".to_owned();
        bean.rating = 4;
        bean.favourite = true;
        bean.grinder = "Niche Zero".to_owned();
        bean.grinder_setting = "1.2".to_owned();
        bean.metadata = serde_json::json!({"co2eKg": 1.4});
        bean
    }

    #[test]
    fn bean_round_trips_through_json() {
        let bean = sample_bean();
        let json = serde_json::to_string(&bean).unwrap();
        let parsed: Bean = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed, bean);
    }

    #[test]
    fn bean_tags_default_when_missing_from_json() {
        // Older persisted Bean records have no `tags` field; the
        // `#[serde(default)]` annotation must give us an empty Vec rather
        // than failing to deserialise. Round-trip a fresh Bean's JSON
        // with the `tags` field stripped to mimic the legacy wire shape.
        let bean = Bean::new("bean:legacy".to_owned(), "x".to_owned(), 0);
        let mut value = serde_json::to_value(&bean).unwrap();
        value.as_object_mut().unwrap().remove("tags");
        let parsed: Bean = serde_json::from_value(value).unwrap();
        assert!(parsed.tags.is_empty());
    }

    #[test]
    fn bean_legacy_metadata_fields_default_when_missing() {
        // Older persisted Bean records predate several non-Option metadata
        // fields. Each now carries `#[serde(default)]`, so a record that omits
        // them must still deserialise (only id + name stay required) rather than
        // failing the whole bean-library load. Mirrors the real-profile fix.
        let bean = Bean::new("bean:legacy".to_owned(), "x".to_owned(), 0);
        let mut value = serde_json::to_value(&bean).unwrap();
        {
            let obj = value.as_object_mut().unwrap();
            for field in [
                "decaf",
                "origin",
                "bagSize",
                "remaining",
                "qualityScore",
                "tastingNotes",
                "rating",
                "notes",
                "favourite",
                "grinder",
                "grinderSetting",
            ] {
                assert!(
                    obj.remove(field).is_some(),
                    "expected `{field}` in the wire shape"
                );
            }
        }
        let parsed: Bean = serde_json::from_value(value)
            .expect("a Bean missing legacy metadata fields must still deserialise");
        assert_eq!(parsed.grinder, "");
        assert_eq!(parsed.grinder_setting, "");
        assert!(!parsed.favourite);
        assert!(!parsed.decaf);
        assert_eq!(parsed.rating, 0);
    }

    #[test]
    fn bean_tags_round_trip() {
        let mut bean = sample_bean();
        bean.tags = vec!["daily-driver".to_owned(), "comp".to_owned()];
        let json = serde_json::to_string(&bean).unwrap();
        let parsed: Bean = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.tags, bean.tags);
    }

    #[test]
    fn roaster_round_trips_through_json() {
        let mut roaster = Roaster::new("roaster:xyz".to_owned(), "Counter Culture".to_owned(), NOW);
        roaster.website = Some("https://counterculturecoffee.com".to_owned());
        roaster.country = Some("USA".to_owned());
        roaster.notes = "Durham NC roastery".to_owned();
        roaster.metadata = serde_json::json!({"founded": 1995});
        let json = serde_json::to_string(&roaster).unwrap();
        let parsed: Roaster = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed, roaster);
    }

    #[test]
    fn roaster_round_trips_new_fields_through_json() {
        // The three roaster-CRUD extension fields (`image_url`, `city`,
        // `canonical_roaster_id`) must survive a serde round-trip with
        // their exact wire spelling so Visualizer-side fields land on the
        // right keys.
        let mut roaster = Roaster::new("roaster:xyz".to_owned(), "Onyx".to_owned(), NOW);
        roaster.image_url = Some("https://onyxcoffeelab.com/logo.png".to_owned());
        roaster.city = Some("Rogers".to_owned());
        roaster.country = Some("Arkansas, USA".to_owned());
        roaster.canonical_roaster_id = Some("roaster:canonical".to_owned());
        let json = serde_json::to_string(&roaster).unwrap();
        // Wire keys are camelCase — matches the TS shell's hand-written
        // Roaster shape in `web/src/lib/bean/model.ts`. The
        // Visualizer-side mapping (`visualizer-sync.ts`) reads
        // `bean.imageUrl` (camelCase) and writes its own snake_case
        // keys to Visualizer's API; this struct's serde shape never
        // reaches Visualizer directly.
        assert!(json.contains("\"imageUrl\":\"https://onyxcoffeelab.com/logo.png\""));
        assert!(json.contains("\"city\":\"Rogers\""));
        assert!(json.contains("\"canonicalRoasterId\":\"roaster:canonical\""));
        let parsed: Roaster = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed, roaster);
    }

    #[test]
    fn roaster_deserialises_legacy_record_without_new_fields() {
        // Pre-extension Roaster records that ride through the JSONL
        // round-trip use the same camelCase shape as the TS shell.
        // The three new optional fields (image_url / canonical_roaster_id /
        // deleted_at) carry `#[serde(default)]` so legacy records
        // without them still parse.
        let legacy = serde_json::json!({
            "id": "roaster:legacy",
            "name": "Counter Culture",
            "website": "https://counterculturecoffee.com",
            "country": "USA",
            "notes": "Durham NC",
            "visualizerId": null,
            "metadata": null,
            "createdAt": 1700000000_i64,
            "updatedAt": 1700000000_i64
        });
        let parsed: Roaster = serde_json::from_value(legacy).unwrap();
        assert_eq!(parsed.id, "roaster:legacy");
        assert_eq!(parsed.name, "Counter Culture");
        assert_eq!(parsed.image_url, None);
        assert_eq!(parsed.city, None);
        assert_eq!(parsed.canonical_roaster_id, None);
    }

    #[test]
    fn shot_bean_round_trips_through_json() {
        let bean = sample_bean();
        let snap = ShotBean::snapshot_of(&bean, Some("Counter Culture"));
        let json = serde_json::to_string(&snap).unwrap();
        let parsed: ShotBean = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed, snap);
        assert_eq!(parsed.bean_id.as_deref(), Some("bean:abc"));
        assert_eq!(parsed.name, "Yirgacheffe");
        assert_eq!(parsed.roaster_name.as_deref(), Some("Counter Culture"));
        assert_eq!(parsed.roasted_on.as_deref(), Some("2026-05-08"));
        assert_eq!(parsed.roast_level, Some(3));
    }

    #[test]
    fn bean_freshness_band_buckets_via_roast_level() {
        let mut bean = Bean::new("bean:1".to_owned(), "x".to_owned(), 0);
        assert_eq!(bean.freshness_band(), None);
        bean.roast_level = Some(1);
        assert_eq!(bean.freshness_band(), Some(RoastBand::Light));
        bean.roast_level = Some(5);
        assert_eq!(bean.freshness_band(), Some(RoastBand::Medium));
        bean.roast_level = Some(9);
        assert_eq!(bean.freshness_band(), Some(RoastBand::Dark));
    }

    #[test]
    fn bean_is_off_roast_only_true_past_ok_window() {
        // A light roast goes Bad at day 36 (LIGHT_WINDOW.ok_high = 35).
        let mut bean = Bean::new("bean:1".to_owned(), "x".to_owned(), 0);
        bean.roast_level = Some(1);
        // 7 days ago — inside the green window.
        bean.roasted_on = Some("2026-05-15".to_owned());
        assert!(!bean.is_off_roast(NOW));
        // 40 days ago — past Light's ok_high (35).
        bean.roasted_on = Some("2026-04-12".to_owned());
        assert!(bean.is_off_roast(NOW));
        // No date → false (defensively).
        bean.roasted_on = None;
        assert!(!bean.is_off_roast(NOW));
        // No level → false.
        bean.roasted_on = Some("2026-04-12".to_owned());
        bean.roast_level = None;
        assert!(!bean.is_off_roast(NOW));
    }

    #[test]
    fn bean_display_summary_joins_name_roaster_days() {
        let bean = sample_bean();
        // Reference NOW is 2026-05-22; roasted_on is 2026-05-08 → 14 days.
        let summary = bean.display_summary(Some("Counter Culture"), NOW);
        assert_eq!(summary, "Yirgacheffe · Counter Culture · 14d off roast");
    }

    #[test]
    fn bean_display_summary_falls_back_to_country_when_name_empty() {
        let mut bean = Bean::new("bean:1".to_owned(), String::new(), NOW);
        bean.origin = BeanOrigin {
            country: Some("Ethiopia".to_owned()),
            ..BeanOrigin::default()
        };
        let summary = bean.display_summary(None, NOW);
        assert_eq!(summary, "Ethiopia");
    }

    #[test]
    fn bean_display_summary_skips_missing_pieces() {
        // No name, no roaster, no date → empty string.
        let bean = Bean::new("bean:1".to_owned(), String::new(), NOW);
        assert_eq!(bean.display_summary(None, NOW), "");
        // Just a name.
        let bean = Bean::new("bean:1".to_owned(), "Geisha".to_owned(), NOW);
        assert_eq!(bean.display_summary(None, NOW), "Geisha");
    }

    #[test]
    fn bean_mix_serialises_lowercase() {
        assert_eq!(
            serde_json::to_string(&BeanMix::Single).unwrap(),
            "\"single\""
        );
        assert_eq!(serde_json::to_string(&BeanMix::Blend).unwrap(), "\"blend\"");
    }

    #[test]
    fn debit_remaining_burns_down_and_floors_at_zero() {
        // Normal debit.
        assert_eq!(debit_remaining(200.0, 18.0), Some(182.0));
        // Exact empty → Some(0) (the run-out case).
        assert_eq!(debit_remaining(18.0, 18.0), Some(0.0));
        // Overshoot floors at 0, never negative.
        assert_eq!(debit_remaining(10.0, 18.0), Some(0.0));
    }

    #[test]
    fn debit_remaining_is_none_when_nothing_to_debit() {
        // Already empty → None: the caller must not re-persist / touch updated_at.
        assert_eq!(debit_remaining(0.0, 18.0), None);
        // Non-positive / non-finite dose → None.
        assert_eq!(debit_remaining(200.0, 0.0), None);
        assert_eq!(debit_remaining(200.0, f32::NAN), None);
        // Non-finite remaining → None.
        assert_eq!(debit_remaining(f32::NAN, 18.0), None);
    }
}

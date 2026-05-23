# 28 — Bean / Roaster Library Design

## Status

2026-05-23 design doc. Read-only audit + opinionated proposal; no code
changes were made.

Sources walked:
- **Beanconqueror** — `/Users/adrianmaceiras/code/Beanconqueror`, current
  `main`; data model in `src/classes/bean/bean.ts`,
  `src/interfaces/bean/iBeanInformation.ts`,
  `src/services/uiBeanStorage.ts`, `src/services/uiExportImportHelper.ts`,
  `src/services/visualizerService/visualizer-service.service.ts`, UI in
  `src/app/beans/` and `src/components/beans/`.
- **Visualizer API v1.8.2** — live fetch of the embedded OpenAPI spec
  (`/coffee_bags`, `/roasters`, `/shots/*`). Auth, schemas, rate limits
  all captured here so the doc is offline-stable.
- **Crema** — `core/de1-domain/src/bean.rs`,
  `core/de1-domain/src/history_export.rs`, `web/src/lib/bean/`,
  `web/src/lib/history/`, the Brew dashboard
  (`web/src/lib/components/brew/`), the Settings section idiom
  (`web/src/lib/components/settings/`), the design tokens in
  `web/src/styles/tokens.css`.

---

## Executive summary

Crema today has a *current bean* — a single bag's worth of metadata in
`web/src/lib/bean/store.svelte.ts:21`, persisted to `localStorage` under
`crema.bean.current.v1`. It carries five fields (`roaster`, `type`,
`roastedOn`, `roastLevel`, `grinder`), snapshots onto each shot, and
drives the freshness dot on `BeanContextCard`. That is the right
foundation but the wrong scope: a real user has 3–8 bags open at any
given time (a fresh espresso, a filter, a frozen stash, a guest pick),
and the single-slot model forces a destructive overwrite every time
they change bags. Worse, there is no recall — every previous bag's
identity dies with the next overwrite, and the shot record's
denormalised `bean` snapshot is the only ghost left.

This doc proposes a **bean library**: many bags, identified by stable
UUIDs; shots reference a bag id (with a denormalised snapshot for
offline portability and history-locks-on-write); a separate **roaster
directory** of roastery records that bags hang off; a one-shot
Beanconqueror JSON import that lets a Beanconqueror user retire their
app in a single click; and an opt-in two-way sync with Visualizer's
`/coffee_bags` and `/roasters` endpoints (HTTP Basic for v1, OAuth
later) so a Visualizer user keeps their existing library. The pitch to
users is consolidation: **one app for the DE1, the library, and the
analytics — keep your Visualizer history, retire Beanconqueror.**

Two principles guide every cut. **One:** Beanconqueror was built by a
home roaster for home roasters, and a third of its model is dead
weight for a DE1-and-Decent-shop espresso user (green-bean tracking,
mill rotation counts, water hardness logs, cupping sliders, CO2e per
kg). Crema's audience is the espresso-pulling DE1 owner; we cut
ruthlessly. **Two:** Visualizer's coffee-bag schema is the de-facto
sync wire format. Whatever fields Crema models, the projection to
Visualizer must be lossless — otherwise sync is a one-way trapdoor and
users will not adopt it. Anything Visualizer does NOT model (favourite
flag, blend ratios, per-bean profile recommendations) lives only in
Crema and rides on a `metadata` JSON blob the API explicitly allows
(`CoffeeBagDetail.metadata: object`).

---

## What we learned from the sources

### Beanconqueror — feature map

Walking `src/classes/bean/bean.ts:21` and `src/interfaces/bean/iBean.ts:14`,
a Beanconqueror bean carries ~40 fields, grouped:

**Identity.** `name`, `roaster` (free text), `buyDate`,
`roastingDate`, `roast` (13-value enum: Cinnamon → French → Custom),
`roast_custom`, `roast_range` (0–5 star intensity),
`beanMix` (`SINGLE_ORIGIN | BLEND | UNKNOWN`), `bean_roasting_type`
(`FILTER | ESPRESSO | OMNI | UNKNOWN`), `note`, `rating` (0–5),
`weight` (g — bag size), `cost`, `url`, `ean_article_number`,
`decaffeinated`, `favourite`, `finished` (archived).

**Origin (array — one per blend component).**
`bean_information: IBeanInformation[]` with `country`, `region`,
`farm`, `farmer`, `elevation`, `harvest_time`, `variety`, `processing`,
`certification`, `percentage`, `purchasing_price`, `fob_price`.

**Roast curve (self-roaster only).** `bean_roast_information`:
`drop_temperature`, `roast_length`, `roaster_machine` (UUID to a
separate `RoastingMachine` class — the user's *home roaster
appliance*, not the third-party roastery), `green_bean_weight`,
`outside_temperature`, `humidity`, `bean_uuid` (link to green bean),
`first_crack_minute/temperature`, `second_crack_minute/temperature`.

**Frozen storage.** `frozenDate`, `unfrozenDate`, `frozenId`,
`frozenGroupId`, `frozenStorageType` (`COFFEE_BAG | JAR | ZIP_LOCK
| VACUUM_SEALED | TUBE`), `frozenNote`. The `beanAgeInDays()` helper
at `bean.ts:246` does literal freeze-decay math with a hardcoded
"90 days frozen = 1 day real" conversion.

**Cupping & flavour.** `cupping` (10-axis SCA form: body, brightness,
clean_cup, complexity, …, sliders 0–10), `cupping_points` (separate
free-text score), `cupped_flavor` (the SCA flavour wheel as a
predefined/custom-tag structure).

**Sharing / sustainability.** `qr_code` (instant-brew URL),
`internal_share_code`, `shared`, `attachments` (bag photos),
`co2e_kg` (manually entered — there is no source data).

**Storage.** Single `BEANS` array in `localStorage` / native FS via
`UIBeanStorage` (`uiBeanStorage.ts:12`). Linear-scan
`getEntryByUUID`. No search index.

**Brew association.** `Brew.bean: string` is a UUID
(`brew.ts:60`); the snapshot lives only on export — at brew time the
bean is read live. The opposite of Crema's
`web/src/lib/history/model.ts:31` `ShotBean`-snapshot approach. **Crema
is right:** Beanconqueror's live-read means renaming a bean silently
rewrites every brew's display name. Snapshot wins.

**Library page.** Search, sort (`LAST_BREW`, `ROASTING_DATE`,
`RATING`, `NAME`, `COST`), filter popover (origin / roast / mix /
archived), one-line cards with photo thumbnail, days-off-roast pill.

**Bean-add.** Four tabs (`beans-add.component.html:7`) — General /
Frozen / Sort Info / Roast Information. The General tab alone exposes
~20 inputs.

**Export format.** `.zip` produced by
`UIExportImportHelper.buildExportZIP` (`uiExportImportHelper.ts:30`):
`Beanconqueror.json` + chunked `Beanconqueror_Beans_*.json` past 500
records, with attachments alongside as referenced files.

**Roaster — no first-class entity.** `Bean.roaster` is free text;
"Onyx Coffee Lab" and "Onyx Coffee" are different roasters as far as
the data knows. The `RoastingMachine` class is the home roasting
appliance, separate concern.

**Mill (grinder).** `Mill { name, note, attachments,
has_adjustable_speed, has_timer }`. Per-brew the grinder is recorded
on `Brew` as `mill` UUID + `grind_size` (free text) + `grind_weight`
+ `mill_speed` + `mill_timer` + `mill_timer_milliseconds` — six fields
counting the ms twin.

**Visualizer sync.** Implemented once
(`visualizerService/visualizer-service.service.ts:98`) and only for
*shot upload*. The bag/roaster CRUD endpoints (added in API v1.8.2)
are not used. Auth is HTTP Basic.

### Visualizer — sync model

Pulled from the live OpenAPI 3.1 spec at
`https://apidocs.visualizer.coffee/`. Server base
`https://visualizer.coffee/api`. Version 1.8.2 as of 2026-05-23.

**Auth.** HTTP Basic (email + password — fine for "personal scripts",
what Beanconqueror uses) or OAuth 2.0 Authorization Code with scopes
`read` / `upload` / `write`. OAuth endpoints
`https://visualizer.coffee/oauth/{authorize,token}`.

**Rate limits.** 50 req/min per IP, 200 req/10min per IP, 200 req/10min
per user. 429 returns `{error: string}`. No retry-after header.

**Premium gating.** Roaster and CoffeeBag *write* endpoints require
premium (€5/mo). Reads don't. A free-tier user can pull their library
but not push new bags.

**Roaster schema.** Sparse — `RoasterDetail` is `{id, name, website,
image_url, canonical_roaster_id}`. That's it. No origin, no contact,
no founding year. A thin lookup, not a CRM.

**Coffee bag schema** — `CoffeeBagDetail`:
- `id, name` (required); `roaster_id`, `canonical_coffee_bag_id` (uuids)
- Dates: `roast_date`, `frozen_date`, `defrosted_date` (date, nullable)
- `roast_level: string | null` — free text, no enum ("Medium-Light",
  "Vienna", whatever)
- Origin (all nullable strings): `country`, `region`, `farm`,
  `farmer`, `variety`, `elevation`, `processing`, `harvest_time`
- `quality_score: string | null` (yes — string, Visualizer gave up
  enforcing numeric scores), `tasting_notes`, `place_of_purchase`,
  `url`, `notes`, `image_url`
- `archived_at: date-time | null`
- **`metadata: object | null`** with `additionalProperties: true` —
  an open JSON bag for app-specific fields. **This is our pressure
  relief valve for Crema-only fields.**

**Endpoints.** Flat REST — `GET/POST /roasters`, `GET/PATCH/DELETE
/roasters/{id}`, same for `/coffee_bags`. `/coffee_bags?roaster_id=…`
filter on list. Nested `/roasters/{r}/coffee_bags/{?}` is deprecated,
301-redirects.

**Shot ↔ bag link.** `ShotDetail` carries `coffee_bag_id` + `roaster_id`
alongside the denormalised `bean_brand`, `bean_type`, `roast_date`,
`roast_level`, `bean_notes` — both worlds, like Crema's `ShotBean`
snapshot. `ShotUpdateRequest` accepts `coffee_bag_id` for after-the-
fact linking.

**Conflict semantics.** Page-based, **no etag, no `If-Match`, no
version vector**. Last-write-wins. Our sync model has to be "local
truth, push deltas", not "merge two libraries".

**List response weirdness.** `CoffeeBagSummary` / `RoasterSummary` are
minimal (`id, name` only). No server-side filter by origin or
roast-date — to find dark Brazilians you fetch every detail page. Fine
at <100 bags.

### What we kill

**1. The 10-axis SCA cupping form.** `IBean.cupping` with its
`body`/`brightness`/`clean_cup`/`complexity` sliders. The DE1 audience
fills this once on a roastery sample, then never again — the median
home espresso user does not cup. It carries ~100 bytes of zeros per
bean and is functionally a museum exhibit of the SCA's hopes for
amateur sensory training. **Replace with:** one `tastingNotes:
string` free-text field (matches Visualizer's `tasting_notes`).

**2. The SCA flavour wheel as a structured form.** `cupped_flavor`
with predefined-flavor scores and custom-flavor strings. A wheel UI is
a beautiful idea that converts to ~3 selected chips for the median user
and then never gets edited again. **Kill it. Free text is enough.**

**3. Mill (grinder) timing fields.** `Brew.mill_timer`,
`mill_timer_milliseconds`, `mill_speed`. Crema's bean already has
`grinder` (the device name) and shot association via the active
profile carries the dose; per-shot grinder *runtime* is a metric for
people optimising single-dosed Niche workflows that no Crema user is
optimising. **Kill mill_timer / mill_speed.** Keep `grinderSetting`
(matches Visualizer `grinder_setting`).

**4. Buy date / Best-by date / Open date — three separate dates.**
Beanconqueror has all three. Pick one. **Crema keeps `roastedOn`
(roast date) and `openedOn` (when the bag was opened, optional);
buyDate and bestBefore are deleted.** Open date matters for staleness,
roast date is the input to freshness, buy date is forensic and
best-before is a guess.

**5. CO2e per kg.** `co2e_kg`. Almost nobody fills this. The few who
do are guessing. Sustainability tracking is real but this
implementation is theater. **Kill.**

**6. EAN article number.** The barcode on the bag.
`ean_article_number`. Useless without a barcode scanner that resolves
EANs to a known product database (there is none for specialty coffee).
**Kill.** Keep `url` (link to the product page) — that's the real
identifier for "the same bag again".

**7. Self-roast curve fields (`bean_roast_information`).**
First-crack temperature, drop temperature, green-bean weight, roast
length, humidity. Crema's audience is not running an Aillio Bullet.
Self-roasters exist but they are < 5% of DE1 owners and they have
better tools (Artisan, RoastMaster). **Kill.** A self-roaster using
Crema can put the curve URL in `tastingNotes`.

**8. Green-bean tracking.** Beanconqueror has a `GreenBean` entity
with its own storage. Same argument as #7. **Kill.** Not even
modelled.

**9. The `roasting_machine` entity.** Same — home-roaster appliance.
**Kill.**

**10. Cupping points field separate from cupping form.** Even after
killing the cupping form, the `cupping_points` field (a free-text
overall score) duplicates `quality_score`. **One field, called
`qualityScore`, matches Visualizer.**

**11. Frozen-group-id portion tracking.** Splitting a 250g bag into
five 50g portions, each with its own freeze/thaw history. Theoretically
useful, practically a configuration nightmare. **Reduce to: a bag has
a single `frozenOn` and `defrostedOn`. If you split, that's two bags.**

**12. Custom roast names.** `roast: ROASTS_ENUM` has 13 named levels
(Cinnamon Roast, City+, Vienna, French …) plus `CUSTOM_ROAST` which
unlocks a free-text `roast_custom`. The Crema bean already uses a
clean 1–10 numeric scale that maps to band words via
`roast_band` (`core/de1-domain/src/bean.rs:122`). **Keep Crema's
1–10. Accept Visualizer's free-text `roast_level` only on import.**

**13. QR-code share / NFC tag generation.** `qr_code`,
`internal_share_code`. The pitch is "stick an NFC tag on your bag and
tap to brew". The market for this is the YouTube reviewers who film
Beanconqueror tutorials, not the DE1 owner pulling a 7am shot.
**Kill — but allow URL import (paste a Beanconqueror share link →
hydrate a bean draft).**

---

## Data model proposal

Two entities — `Bean` (a bag) and `Roaster` — plus a snapshot type on
the shot.

### Bean entity

TypeScript (`web/src/lib/bean/model.ts`):

```typescript
export interface Bean {
  readonly id: string;                  // "bean:<uuid>"
  name: string;                          // required; Visualizer `name`
  roasterId: string | null;              // FK into roaster table

  // Dates — all ISO yyyy-mm-dd, all nullable. Map 1:1 to Visualizer.
  roastedOn: string | null;
  openedOn: string | null;               // Crema-only — staleness signal
  frozenOn: string | null;
  defrostedOn: string | null;

  roastLevel: number | null;             // 1..10 (Crema canonical);
                                         // projects to Visualizer's free-text
  mix: 'single' | 'blend' | null;
  decaf: boolean;

  // Origin: flat fields, NOT an array. A blend has slashes in `country`.
  origin: {
    country: string | null;              // "Ethiopia / Colombia"
    region: string | null;               // "Yirgacheffe"
    farm: string | null;
    farmer: string | null;
    variety: string | null;              // "Geisha", "SL28 / SL34"
    elevation: string | null;            // "1900-2100 masl"
    processing: string | null;           // "Natural", "Washed", "Anaerobic"
    harvestTime: string | null;
  };

  bagSizeG: number;                      // 0 when unknown
  remainingG: number;                    // auto-debited per shot

  qualityScore: string;                  // free text — "88", "A-", "🔥🔥🔥"
  tastingNotes: string;                  // replaces Bc cupping + flavor wheel
  rating: number;                        // 0..5 (0 = unrated)

  placeOfPurchase: string | null;
  url: string | null;                    // link to buy again
  notes: string;

  favourite: boolean;                    // pinned to brew picker strip
  archivedAt: number | null;             // Unix ms; null = active

  grinder: string;                       // free text — bean-scoped device
  grinderSetting: string;                // bean-scoped click / setting

  visualizerId: string | null;           // set after first push
  beanconquerorId: string | null;        // import provenance
  imageRef: string | null;               // IndexedDB blob key

  createdAt: number;
  updatedAt: number;
}
```

Rust counterpart (`core/de1-domain/src/bean_library.rs`, new):

```rust
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Bean {
    pub id: String,                        // "bean:<uuid>"
    pub name: String,
    pub roaster_id: Option<String>,
    pub roasted_on: Option<String>,        // ISO yyyy-mm-dd
    pub opened_on: Option<String>,
    pub frozen_on: Option<String>,
    pub defrosted_on: Option<String>,
    pub roast_level: Option<u8>,           // 1..10
    pub mix: Option<BeanMix>,              // Single | Blend
    pub decaf: bool,
    pub origin: BeanOrigin,                // flat struct, all Option<String>
    pub bag_size_g: f32,
    pub remaining_g: f32,
    pub quality_score: String,
    pub tasting_notes: String,
    pub rating: u8,
    pub place_of_purchase: Option<String>,
    pub url: Option<String>,
    pub notes: String,
    pub favourite: bool,
    pub archived_at: Option<i64>,          // Unix ms
    pub grinder: String,
    pub grinder_setting: String,
    pub visualizer_id: Option<String>,
    pub beanconqueror_id: Option<String>,
    pub image_ref: Option<String>,
    pub created_at: i64,                   // Unix ms
    pub updated_at: i64,
}
```

The core owns the type so the Android shell consumes the same shape
on day one (audit push #11 trajectory in `docs/26-shell-to-core-audit.md`).
Conversion to/from Visualizer wire is a pure function — `to_visualizer`
returns the open JSON object the API accepts, `from_visualizer`
parses one. Tested round-trip in the core, same pattern as
`history_export.rs`.

### Roaster entity

```typescript
export interface Roaster {
  /** Stable UUID — `roaster:<uuid>`. */
  readonly id: string;

  /** Roastery name. REQUIRED. Matches Visualizer `roaster.name`. */
  name: string;
  /** Roastery website. Visualizer `roaster.website`. */
  website: string | null;
  /** City / state / country. Crema-only; rides on `metadata`. */
  location: string | null;
  /** Roastery logo / hero. */
  imageRef: string | null;

  /** Visualizer id once pushed. */
  visualizerId: string | null;
  /** Beanconqueror imported only the free-text roaster name, so on
      import we synthesise a Roaster row from the unique names — this
      tracks the source string for dedup. */
  beanconquerorName: string | null;

  createdAt: number;
  updatedAt: number;
}
```

Critical: Beanconqueror stores `Bean.roaster` as a free-text string.
On import, Crema *promotes* unique roaster strings into Roaster rows,
keyed by case-insensitive normalised name, and rewrites bean
`roasterId` references. "Onyx Coffee Lab" and "onyx coffee lab" merge.
"Onyx Coffee" (without "Lab") stays separate — the user can manually
merge later via a "Combine these roasters?" review screen.

### Bean ↔ shot association

The shot record continues to carry a *denormalised snapshot*
(`web/src/lib/history/model.ts:31`'s `ShotBean`) — the right call,
already in place. We *add* a `beanId: string | null` field next to the
snapshot. Render priority: bean still in library → click-through to
the bean detail; bean archived or deleted → snapshot still renders the
History row from the embedded fields.

```typescript
export interface ShotBean {
  readonly beanId: string | null;   // NEW — link into the library
  readonly roaster: string;          // existing snapshot
  readonly type: string;             // existing snapshot
  readonly roastedOn: string | null;
  readonly roastLevel: number | null;
}
```

The Visualizer `ShotDetail.coffee_bag_id` and `roaster_id` map
directly to `beanId` + the bean's `roasterId`. On shot upload, if the
bean has a `visualizerId`, include `coffee_bag_id` in the upload
payload; otherwise push the bag first, then the shot.

### Storage

**Web shell.** `localStorage` keys, mirroring the existing single-bean
pattern:

- `crema.beans.library.v1` — JSON-encoded `{ beans: Bean[], roasters:
  Roaster[], schemaVersion: 1 }`. One array each for the foreseeable
  scale (median user <100 bags ever; Beanconqueror chunks at 500 and
  that's already extreme).
- `crema.beans.activeBeanId.v1` — the currently-selected bean's id, or
  `null`. Replaces today's single-bean store.
- `crema.beans.sync.v1` — `{ visualizerUrl, visualizerEmail,
  visualizerPasswordEncrypted, lastSyncAt, knownVisualizerIds:
  Record<string,string> }`. The Visualizer credentials live here, not
  in app settings, because they're the bean library's concern.

**IndexedDB** for `imageRef` blobs — bag photos easily exceed
localStorage's 5 MB quota. A separate `crema-bean-images` IDB
object store keyed by bean id, with a `(beanId, blob, mime)` row.

**Schema versioning.** A `schemaVersion: 1` envelope is mandatory.
The current `web/src/lib/bean/store.svelte.ts:13` is `crema.bean.current.v1`
without a version inside — we keep that key and migrate it to a
single-entry library on first load, then write the new envelope.

**Why not the Rust core's persistence?** Today Crema's web shell
persists *everything* to `localStorage` directly (profiles, history,
the current bean), and the Rust core is sans-IO. Same pattern here —
the core defines the type and the (de)serialisation rules; the shell
owns the persistence. When Android lands, it gets its own Room
adapter behind the same type contract.

### Sync model

**Phase 1 — local only.** Library lives in localStorage. No network.
This is the entire MVP; everything else is icing.

**Phase 2 — Visualizer push.** Opt-in. User enters email + password in
Settings → Sharing → Visualizer (HTTP Basic, copying the
Beanconqueror approach for now). A "Sync library" button runs:

1. `GET /coffee_bags?items=100&page=*` until paged-out. For each
   remote bag with a `metadata.crema_id` we recognise, store the
   `visualizerId` mapping. New remotes get pulled into the local
   library as full beans (a "Pull" step the user previews).
2. For every local bean *without* a `visualizerId`, find its roaster:
   if the roaster has no `visualizerId` either, `POST /roasters`
   first, capture the id, then `POST /coffee_bags` with `roaster_id`.
3. For every local bean *with* a `visualizerId` whose `updatedAt > 
   lastSyncAt`, `PATCH /coffee_bags/{id}`.
4. Archived locally → `PATCH` with `archived_at` set.

Crema-only fields (`favourite`, `bagSizeG`, `remainingG`, `mix`,
`decaf`, `beanconquerorId`, `crema_id`, `grinder`, `grinderSetting`,
`rating`) ride on `metadata: { crema: { ... } }` — Visualizer
explicitly allows `additionalProperties` on that field
(paths.yaml:1733).

**Conflict resolution.** Last-write-wins on `updatedAt`. There is no
etag; we accept the race. The sync log surfaces "remote
overwrote local change on X" entries so the user knows.

**Phase 3 — OAuth.** Once Crema needs to be redistributable
(public PWA, store-listed Android), HTTP Basic is unacceptable — we
should not be in the password-collection business. Move to the OAuth
authorization-code flow Visualizer documents. Out of scope for the
first ship.

**Rate-limit safety.** 200 req/10min per user, and a full library
push of 50 bags + 20 roasters is 70 writes — well within budget.
Batched syncs throttle at 5 writes/sec and stop on 429.

---

## UX

### Library view

A new top-level route `/beans` (sibling of `/history`, `/profiles`,
`/settings`). The layout deliberately echoes `/profiles` so the user
maps it instantly:

```
┌────────────────────────────────────────────────────────────────────┐
│ Beans                                          [+ Add bean]  [⋯]   │
│ ────────────────────────────────────────────────────────────────── │
│ [Search…]   [All ▾] [Roast ▾] [Origin ▾] [Active / Archived]       │
│                                                                    │
│ ┌─Active────────────────────────────────────────────────────────┐  │
│ │ ● ☕  Onyx · Geisha Esmeralda          Light · 7d off roast    │  │
│ │   📷  Ethiopia · Yirgacheffe            ★★★★☆  ▮▮▮▮▮▮▯ 142g    │  │
│ ├───────────────────────────────────────────────────────────────┤  │
│ │ ○ ☕  Sey · Kenya Kii                  Medium · 14d           │  │
│ │   📷  Kenya · Nyeri                     ★★★★★  ▮▮▮▯▯▯▯  58g    │  │
│ └───────────────────────────────────────────────────────────────┘  │
│                                                                    │
│ ┌─Frozen────────────────────────────────────────────────────────┐  │
│ │   ☕  Hydrangea · Pink Bourbon         Light · 38d (frozen 6d) │  │
│ │                                                                │  │
│ └───────────────────────────────────────────────────────────────┘  │
│                                                                    │
│ ┌─Archived (2)──────────────────────────────────────────────── ▾ │  │
│ └───────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────┘
```

Spatially: a left rail with section headers (Active / Frozen /
Archived), the active bean carrying a copper dot at the left edge (the
same accent the active profile uses in `ProfilePreview` —
`web-kit.css`'s `.is-active` treatment). The rest pill on the right
uses the same freshness colour the brew dashboard already has
(`BeanContextCard.svelte:60-68`): `var(--success)` / `var(--warning)`
/ `var(--danger)`. A `▮▮▮▮▮▮▯` burn-down bar shows `remainingG /
bagSizeG` — tiny, no label, the most-information-per-pixel design we
can fit (idea borrowed from `MiniShotChart`'s
"information-dense miniature" philosophy).

Tap a row → opens the detail / edit drawer on the right (desktop) or
fullscreen sheet (mobile), same pattern as the `/history` page's
ShotDetail.

**Empty state.** Two big CTAs side by side: "Add your first bag" and
"Import from Beanconqueror". The Visualizer pull is a third
secondary action below.

**Bulk operations.** A long-press / shift-click enters select mode
(echo `ShotRow.svelte`'s `selectable` mode) — Archive, Delete, Merge
roaster. The Compare-shots pattern from history is the visual model;
the cap there is 4, here it's open-ended.

### Bean detail / edit view

A drawer (right-rail on desktop, full sheet on mobile). The body uses
the existing `StGroup` / `StRow` settings rhythm so it feels native
the moment the user opens it.

```
┌─Onyx · Geisha Esmeralda ─────────────────────[Active ●] [⋯]─┐
│ [📷 bag photo (160×160 if present)]                          │
│                                                              │
│ ─Identity ────────────────────────────────────────────────── │
│ Name           [Geisha Esmeralda Lot 3            ]          │
│ Roaster        [Onyx Coffee Lab           ▾]   (+ new)       │
│ Roast level    [— 1·····5·····10 +]    Light · Medium · Dark │
│                                                              │
│ ─Dates ──────────────────────────────────────────────────── │
│ Roasted on     [2026-05-16]    7d off roast · ● fresh        │
│ Opened on      [2026-05-19]    4d open                       │
│ Frozen?        [Off]                                         │
│                                                              │
│ ─Bag ────────────────────────────────────────────────────── │
│ Size           [250 g]  Remaining [142 g]  (auto-debit ✓)    │
│ Mix            ( Single  ‣ Blend )                           │
│ Decaf          [Off]                                         │
│                                                              │
│ ─Origin (collapsed by default) ─────────────────────────── ▾ │
│                                                              │
│ ─Tasting ────────────────────────────────────────────────── │
│ Score          [88]                                          │
│ Notes (multi-line) [_____________________________________]   │
│ Rating         ★★★★☆                                         │
│                                                              │
│ ─Buy again ────────────────────────────────────────────── ▾ │
│   URL · Place of purchase                                    │
│                                                              │
│ ─Shots with this bean ─────────────────────────────────────  │
│   [MiniShotChart] 09:14 — Geisha v3, 1:2.1, ★★★★              │
│   [MiniShotChart] 14:01 — Geisha v3, 1:2.4, ★★★                │
│   …                                                          │
│                                                              │
│ [Delete]                              [Make active]          │
└──────────────────────────────────────────────────────────────┘
```

Emphasis order (top to bottom): identity (you need the name + roaster
above the fold), then dates (the freshness pill is the at-a-glance
hook), then bag stats. Origin is collapsed because for blends it bloats
fast, and an espresso user rarely opens it after the first save.
Tasting near the bottom because it's the field that's most often left
empty.

The "Shots with this bean" list at the foot is a `ShotRow` reuse —
filtered to this bean's id, scrollable inline, sparkline column
included. This is *the* feature that earns the library's keep: you can
finally answer "what does the Onyx Geisha taste like at 92°C with my
Best Practice profile?"

### Brew page integration

Today the BrewDashboard's left column hosts `BeanContextCard` (the
single-bean badge at `web/src/lib/components/brew/BeanContextCard.svelte`).
**We keep it.** It still shows the *active* bean, freshness dot, days
off roast.

The change: tap the card → instead of opening an inline editor, open a
**bean picker** — a horizontal scrolling strip identical in design to
`FavoritesStrip` (the profile picker at
`web/src/lib/components/brew/FavoritesStrip.svelte`), populated with
**favorited** beans + a "+ All beans…" trailing chip.

```
┌── Choose bean ─────────────────────── [Search…] [< >] ──┐
│  ┌─Active┐  ┌──────┐  ┌──────┐  ┌──────┐  ┌──────────┐  │
│  │ Onyx  │  │ Sey  │  │ Hydr.│  │ April│  │ + All    │  │
│  │ Geisha│  │ Kenya│  │ Pink │  │ Yemen│  │   beans  │  │
│  │ ● 7d  │  │ ● 14d│  │ ●38d │  │ ● 12d│  │          │  │
│  └───────┘  └──────┘  └──────┘  └──────┘  └──────────┘  │
└─────────────────────────────────────────────────────────┘
```

This mirrors the profile favorites idiom — `ProfilePreview compact` is
the chip body for profiles, a `BeanChip compact` becomes its bean
analogue (same 70×30 footprint, same `.is-active` copper treatment,
same `qfstrip-arrow` nudge buttons). The pinning concept is identical:
beans flagged `favourite = true` show in the strip; the rest live one
tap deeper.

**Why a picker over a dropdown?** A dropdown buries the freshness
signal. The strip is glanceable — the four bags you cycle through this
week are right there with their freshness dots visible, and switching
is one tap.

**Auto-suggest based on profile.** *Not* in v1. The "this bean loved
this profile" inference is a real feature (see Areas to innovate) but
it requires enough shot history per bean+profile pair to be useful.
Ship the explicit picker first, layer suggestions on once the data
exists.

**Quick-add a bean inline.** A small `+` button at the right edge of
the BeanContextCard opens a quick-add row: just Name + Roaster + Roast
date, save → instant active. Skip the full editor for the impatient
case (you bought a bag on the way to work, you want to log it in 10
seconds before pulling the first shot).

**Auto-debit on shot.** When a shot completes, deduct
`dose` (from the active profile) from `bean.remainingG`. The user can
disable in Settings ("Track bag remaining weight"). Default on.

### Roaster directory

A second tab on `/beans`, NOT a separate route. Roasters are an
implementation detail for users — they don't go shopping for roasters,
they go shopping for bags. A tab is cheap; a top-level route is a
commitment.

```
/beans         ← Bags tab (default)
/beans?tab=roasters
```

A roaster card carries the logo (if any), name, website link as a
small chip, and a count of bags. Tap → roaster detail shows
`{name, website, location}` + the list of bags from that roaster
(MiniShotChart-free, just bean rows).

The "Combine these?" merge tool lives here — a roaster card with
< 3 bags and a near-duplicate name surfaces a small banner: "Looks
like 'Onyx Coffee' and 'Onyx Coffee Lab' might be the same roaster.
Merge?" with a one-click merge that rewrites all bean `roasterId`
refs to the chosen survivor.

### Import flow

A "Import" button in the `/beans` header `⋯` overflow.

Two sources:
1. **Beanconqueror `.zip`**. User drops the file. We unzip in-browser
   (`zip.js`, same lib Beanconqueror itself uses for export). We parse
   `Beanconqueror.json` + every `Beanconqueror_Beans_*.json` chunk
   into the `IBean[]` shape, and present a preview:

   ```
   ┌─ Import from Beanconqueror ───────────────────────────────┐
   │ Found 67 beans, 12 unique roasters, 412 brews.            │
   │                                                            │
   │ Import:                                                    │
   │  ☑ 12 roasters (will be created)                          │
   │  ☑ 67 beans (3 will be merged with existing — review)     │
   │  ☐ 412 brews → /history records                           │
   │                                                            │
   │ Skipping (data Crema doesn't model):                       │
   │  ☐ Self-roast curve data (12 self-roasted beans)           │
   │  ☐ Cupping form fields (53 beans had filled cupping)       │
   │  ☐ Green beans, mills, waters                              │
   │                                                            │
   │                            [Cancel]  [Import 67 beans]    │
   └────────────────────────────────────────────────────────────┘
   ```

   Field mapping (the load-bearing rows):
   - `name`, `note → notes`, `rating`, `decaffeinated → decaf`,
     `beanMix → mix`, `url`, `weight → bagSizeG` are 1:1.
   - `roaster` → resolve/create Roaster.
   - `roastingDate`, `openDate`, `frozenDate`, `unfrozenDate` → the
     four ISO dates.
   - `roast` (13-value enum) → `roastLevel` via lookup
     (Cinnamon=1, City=4, City+=5, Full City=6, Italian=8, French=10).
   - `aromatics + cupping.notes + cupping_points` → concatenated into
     `tastingNotes`; `cupping_points` also → `qualityScore`.
   - `bean_information[0]` → `origin.*` (drop blend-component array;
     full blend breakdown lands in `tastingNotes` as text).
   - `attachments[0]` → `imageRef` (load blob from zip).
   - `finished` → `archivedAt = importedAt`.
   - Discarded (with import-log count): `qr_code`,
     `internal_share_code`, `co2e_kg`, `ean_article_number`,
     `cupping.*` (10 axes), `cupped_flavor` (flavor wheel),
     `bean_roast_information.*` (self-roast curve),
     `bestDate`, `buyDate`, `cost`, `frozenId`, `frozenGroupId`,
     `frozenStorageType`, `frozenNote`.

   `Brew[]` → `StoredShot[]` is its own import (out of scope for
   the bean library — same module already round-trips v2 shots, the
   Beanconqueror brew shape becomes a third source). Show the toggle
   but ship it later.

2. **Visualizer pull** (Settings → Sharing). Lists every bag we don't
   already mirror; the user ticks which to import.

**Conflict resolution.** Two beans with the same case-insensitive
`(roasterName, name, roastedOn)` triple are duplicates. The preview row
offers "merge → keep newer fields where filled" / "skip" / "create
both".

### Sync flow

In Settings → Sharing → Visualizer, the existing shot-upload section
(which already exists in the codebase per
`web/src/lib/components/settings/sections/SharingSection.svelte`) grows
a second card:

```
┌─Bean library sync ─────────────────────────────────────────┐
│ Status:  ●  In sync — last 2 min ago                       │
│           67 beans, 12 roasters                             │
│                                                             │
│ [Sync now]                  [Pause auto-sync]               │
│                                                             │
│ Recent activity:                                            │
│   ↑  Pushed "Geisha Esmeralda Lot 3"      2 min ago         │
│   ↑  Updated "Onyx Coffee Lab" website   14 min ago         │
│   ↓  Pulled "April · Yemeni Sanani"      1 h ago            │
│                                                             │
│ Auto-sync:  ✓  On bean change                               │
│             ✓  On shot completion (links shot to bag)       │
└────────────────────────────────────────────────────────────┘
```

First-run requires connecting an account — re-use the existing
`visualizer_username` / `visualizer_password` settings (already
managed by `web/src/lib/state` per the existing
`uploadToVisualizer` plumbing in Beanconqueror — Crema's mirror code
follows the same pattern).

A connection failure surfaces a red banner ("Visualizer credentials
rejected · Update in Settings"), exactly like the
`MachineErrorBanner` treatment for BLE failures.

---

## Areas to innovate

Five features neither Beanconqueror nor Visualizer have. Picked because
each rides on data Crema *already collects* and has zero analogue in
the source apps.

**1. Per-bean profile recommendation.** Once `bean.shotCount >= 5`,
compute mean star rating per `(beanId, profileId)`. On the brew page,
when a bean is picked, a chip under the profile favorites reads "Tip —
this bean's best on **Allonge 1:3** (avg ★★★★☆ across 7 shots)", one
tap to switch profiles. No ML, no embeddings, just a `GROUP BY`.
Beanconqueror tracks brews per bean but never surfaces the
*recommendation*. This is the killer feature.

**2. Bag burn-down + reorder reminder.** `remainingG` auto-debits the
active profile's dose every shot. When `remainingG <= 2 * doseG`, the
BeanContextCard shows an amber chip "2 shots left — reorder?" tapping
through to the bag's `url`. Closes the buy-again loop the morning the
bag runs out. Manual override mandatory for refill / split bags.

**3. Best-shot-with-this-bean.** "Best: ★★★★★ · 1:2.3 · 24 s · 14d off
roast · Best Practice" on the bean detail above the shot list. Trivial
query (top-rated shot filtered by bean), high recall payoff — the user
replicates their own win.

**4. Freshness band on the live LiveChart.** When a bean is active,
draw a 3-px horizontal band on the chart's right axis showing the
predicted-best brew temperature range for the roast band (dark
89–92 °C, light 93–95 °C). Mostly invisible — only loud enough to
nudge "your setpoint is high for this dark roast". Beanconqueror has
nothing brew-time-active.

**5. Photo + OCR for the bag label.** Capture via `<input type="file"
accept="image/*" capture>`, run OCR via `Shape Detection API` where
available, skip OCR otherwise. The high-value extraction is **roast
date** — most specialty bags print `yyyy-mm-dd` or `dd/mm/yyyy` and a
regex catches it. Beanconqueror stores attachments; nobody reads them.

---

## Build sequence

Seven milestones, atomic, each independently shippable.

**M1 — Core library types.** Add `core/de1-domain/src/bean_library.rs`
with `Bean`, `Roaster`, `BeanMix`, `BeanOrigin` Rust types. Round-trip
serde tests against a fixture JSON document. No wasm export yet — the
shell uses its own TypeScript shape until M3.

**M2 — Shell library store + UI scaffold.** Replace
`web/src/lib/bean/store.svelte.ts`'s single-bean store with a
`BeanLibraryStore` holding `beans[]`, `roasters[]`, `activeBeanId`. Add
a `/beans` route with the Library view (read-only first), the
BeanContextCard now reads `library.activeBean`, the existing
`crema.bean.current.v1` payload auto-migrates into a single-bean
library on load.

**M3 — Bean editor.** The detail drawer with `StGroup` / `StRow`
sections, photo via `<input type="file" accept="image/*" capture>`.
Add/delete/archive/star. Roaster directory with the dedup nudge.

**M4 — Brew picker integration.** `BeanChip` component, the
horizontal picker on the BeanContextCard tap, "+ All beans" overflow
sheet, quick-add inline. Auto-debit `remainingG` on shot completion.

**M5 — Beanconqueror import.** A drop-zone modal under the `/beans` ⋯
overflow. Parse `.zip`, preview, commit. Field map per the table
above. Brew import deferred.

**M6 — Visualizer sync v1.** HTTP-Basic, manual "Sync now" button,
push-then-pull diff, conflict log. Map `metadata.crema_id` round-trip
for stable identity.

**M7 — Innovate.** The five innovations land separately in priority
order: per-bean profile recommendation (highest payoff), bag
burn-down, best-shot-with-this-bean, freshness curve overlay, OCR-from-photo.

Sequence cost: M1–M4 are the MVP — Crema with a usable bean library,
no sync, no import. ~3 weeks of one engineer. M5 unlocks switching
costs for Beanconqueror users. M6 unlocks Visualizer keepers. M7 is
the differentiation kit.

---

## Open questions

1. **Photo storage boundary.** IndexedDB is the right tier; the wasm
   core can't see it. Rust `Bean` carries `image_ref:
   Option<String>` as opaque. Confirm shell owns blob storage
   entirely (matches `docs/26-shell-to-core-audit.md`).

2. **Brew import scope.** Beanconqueror's `Brew[]` is rich; Crema's
   `StoredShot` is a strict subset (no telemetry samples). Lossy import
   in M5, deferred M5b, or refuse entirely?

3. **OAuth vs Basic timing.** v1 Basic. Move-trigger: PWA store
   listing, or first user report of creds-in-localStorage.

4. **Active bean per profile vs global.** One active bean today. A
   filter-roasted bag + an espresso-roasted bag both open is latent
   demand for bean↔profile pinning. Defer; revisit after M4 with usage
   data.

5. **Visualizer free-tier reality.** Bag CRUD is premium-gated. Many
   users will be read-only. Surface "free tier — pull only" in the
   connection-test response.

6. **Bc brew → Visualizer shot bulk-import.** Out of scope; own doc.
   Confirm.

---

## Out of scope (for now)

Explicit cuts so M1–M7 stay honest:

- Water hardness tracking, water recipe library (Beanconqueror's
  `IWater` model). Crema has a tank-level readout; a chemistry log
  belongs in a future doc, not this one.
- Mill / grinder as a first-class entity with attachments. Today the
  grinder is two free-text fields on the bean; that's enough.
- Self-roast curve capture. If you're roasting at home, you're using
  Artisan.
- Cupping form (SCA 10-axis sliders). Killed.
- Flavour wheel / structured tasting form. Killed.
- CO2e per kg sustainability metric. Killed.
- EAN barcode scanning. Killed.
- QR-code / NFC tag generation for shareable beans. Out of scope —
  reconsider if we ship a community feature.
- Cost / spend tracking. Out of scope; arguably a future
  finance-flavoured tab but not a bean concern.
- Multi-user / shared library. Crema is single-device single-account
  for the foreseeable future.
- Importing from third-party sources other than Beanconqueror and
  Visualizer (Cropster, RoasterCentral, Beanhopper). None has a critical
  mass of DE1 users.
- OAuth flow against Visualizer. Phase 3 only.
- Shot bulk-import from Beanconqueror's `Brew[]`. Own doc.
- Server-side dedup against Visualizer's `canonical_coffee_bag_id`
  catalogue. Hooks present (we send the id through); UX deferred until
  Visualizer publishes their canonical list more visibly.

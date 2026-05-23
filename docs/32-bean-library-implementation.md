# 32 — Bean library implementation

## Status

2026-05-23 — Shipped on branch `bean-library`. The design lives in
`docs/28-bean-roaster-library.md`; this doc summarises what was actually
built, what was cut, and what's left as a TODO. Gates green: `cargo
test --workspace`, `cargo clippy --workspace --all-targets -- -D
warnings`, `pnpm check`, `pnpm build` all pass.

---

## What shipped

### Phase 0 — Canonical types in `de1-domain`

Extended `core/de1-domain/src/bean.rs` with the bean library entities,
each `#[typeshare]`-annotated so the Android shell consumes the same
shape on day one:

- **`Bean`** — one bag of coffee, the central row in the library.
  Carries id (`bean:<uuid>`), name, roaster id, dates (roasted /
  opened / frozen / defrosted), 1..10 roast level, mix
  (`BeanMix::Single|Blend`), decaf flag, `BeanOrigin` block (flat
  country/region/farm/farmer/variety/elevation/processing/harvest_time),
  bag size + remaining grams, quality score, tasting notes, rating,
  place of purchase, url, notes, favourite, archived_at, grinder +
  grinder setting, visualizer id, beanconqueror id, image ref, and a
  `serde_json::Value` `metadata` escape valve (round-trips with
  Visualizer's `CoffeeBag.metadata: object` lossless).
- **`Roaster`** — id, name, website, country, notes, visualizer id,
  metadata, timestamps. Mirrors Visualizer's `RoasterDetail`.
- **`ShotBean`** — denormalised snapshot frozen onto each completed
  shot (bean_id + name + roaster_name + roasted_on + roast_level).
  `ShotBean::snapshot_of(bean, roaster_name)` builds one. Per docs/28
  §design-decisions §1, snapshot beats Beanconqueror's live-read so a
  later rename does not retroactively rewrite history.

Pure helpers on `Bean`:

- `Bean::freshness_band()` — `RoastBand` bucket via `roast_band`.
- `Bean::is_off_roast(now_unix_ms)` — past the band's stale-out window.
- `Bean::display_summary(roaster_name, now)` — `"Name · Roaster · 14d
  off roast"` with graceful piece-omission for empty fields.

**12 new tests** in `bean::tests` cover JSON round-trip for all three
types, freshness band boundary, is_off_roast above/below threshold,
display_summary fallback to country / empty pieces, and BeanMix
lowercase wire format.

CRUD logic stays in the shell — core is sans-IO. Files touched:
- `/Users/adrianmaceiras/code/crema/core/de1-domain/src/bean.rs`
- `/Users/adrianmaceiras/code/crema/core/de1-domain/src/lib.rs`

### Phase 1 — Shell library + brew integration

#### Bean library store
- `web/src/lib/bean/model.ts` — TS mirror of the Rust types
  (`Bean`, `Roaster`, `BeanOrigin`, `BeanMix`, `Freshness`,
  `LegacyCurrentBean`), `blankBean` / `blankRoaster`,
  `coerceBean` / `coerceRoaster` defensive deserialisers,
  `migrateLegacyCurrentBean` (promotes the old single-bean
  `crema.bean.current.v1` into a favourited library row on first
  load — no data loss), `beanDisplaySummary` helper.
- `web/src/lib/bean/store.svelte.ts` — `BeanLibraryStore`,
  Svelte 5 `$state.raw`. Versioned localStorage envelope at
  `crema.beans.v1`; active pointer at `crema.beans.activeBeanId.v1`.
  CRUD: `upsertBean`, `updateBean`, `deleteBean`, `toggleFavourite`,
  `toggleArchived`, `bulkAdd` (import path), `ensureRoaster`
  (inline-create + import dedup), `debitFromActive` (burn-down on
  shot completion), `quickAdd`, `replaceBean` (sync path).
  Back-compat alias `getBeanStore()` so pre-library callers keep
  working.

#### Routes + UI
- `web/src/routes/beans/+page.svelte` — the `/beans` library page.
  Header (search, Import, New bag), tabs (Bags / Roasters), filter
  chip strip (All / Pinned / Frozen / Decaf / Archived), sort
  (Recent / Roast date / Name / Rating), responsive 3-column card
  grid. Roaster directory tab with bag-count subline.
- `web/src/lib/components/beans/BeanEditor.svelte` — right-rail
  drawer with collapsible Origin and Buy-again sections, inline-
  create roaster, rating stars, refill/mark-empty quick actions,
  "Shots with this bag" list (per-bean history + best-shot-so-far
  surface).
- `web/src/lib/components/beans/BeanStrip.svelte` — brew-page bean
  picker mirroring `FavoritesStrip`'s profile pattern (docs/28
  §brew-page-integration). Horizontally scrolling chip strip,
  freshness dot per chip, +All beans overflow link, inline search.
- `web/src/lib/components/brew/BeanContextCard.svelte` — rewritten on
  top of the library. Tap to edit the active bag, `+` to quick-add,
  empty state routes to `/beans`.
- `web/src/lib/components/brew/BrewDashboard.svelte` — host the new
  BeanStrip just below the profile header.
- `web/src/lib/components/CremaSidebar.svelte` — new "Beans" nav
  entry (key 3). Profiles=2, History=4, Scale=5, Settings=6 shifted.

#### Shot snapshot wiring
- `web/src/lib/state/app.svelte.ts` — on shot completion, snapshot
  the active library bean onto the `StoredShot.bean` (extended with
  `beanId` so the History UI can resolve click-through) and debit
  the bag's `remainingG` by the active profile's dose.
- `web/src/lib/history/model.ts` — `ShotBean` extended with optional
  `beanId` for the library FK. Existing `roaster`/`type`/`roastedOn`/
  `roastLevel` snapshot fields preserved so v2 export, import, and
  the History UI keep working unchanged.

#### Innovations shipped
1. **Per-bean profile recommendation** (`/beans` card subline) —
   "Best with `<profile>` · avg ★X.X over N shots" surfaces once
   `>=3` rated shots per bean+profile pair. `GROUP BY beanId,
   profileName` → mean rating. No ML.
2. **Bag burn-down + reorder hint** — `remainingG` auto-debits on
   shot completion in `app.svelte.ts`. BeanContextCard surfaces
   "X shots left — reorder?" when remaining grams ≤ 2× the proxy
   dose. The library card shows a `▮▮▮▮▮▯▯▯` bar with the remaining
   grams.

### Phase 2 — Beanconqueror import

- `web/src/lib/components/beans/BeanImportDialog.svelte` — file-
  picker / drag-drop modal. Uses the existing `fflate` dep for the
  zip walk, parses `Beanconqueror.json` + chunked `_Beans_*.json`
  files, builds a preview, commits with `library.bulkAdd`.

Per the coordinator's mid-flight adjustment, the importer is
**deliberately lossy**:

| Crema field | Beanconqueror source | Notes |
|---|---|---|
| `name` | `name` | 1:1 |
| `roasterId` | resolved/created from `roaster` string | dedup case-insensitive |
| `roastedOn` | `roastingDate` (first 10 chars) | 1:1 |
| `openedOn` | `openDate` | 1:1 |
| `frozenOn` / `defrostedOn` | `frozenDate` / `unfrozenDate` | 1:1 |
| `roastLevel` | `roast` 13-band enum → 1..10 | mapped via `BC_ROAST_MAP` |
| `mix` | `beanMix` | `SINGLE_ORIGIN` → `single`, `BLEND` → `blend` |
| `decaf` | `decaffeinated` | 1:1 |
| `notes` | `note` | 1:1 |
| `rating` | `rating` (0..5) | clamped |
| `favourite` | `favourite` | 1:1 |
| `url` | `url` | 1:1 |
| `bagSizeG` / `remainingG` | `weight` | assume full bag on import |
| `archivedAt` | `finished` flag | stamped to import time |
| `origin.*` | `bean_information[0]` block | first blend component only |
| `qualityScore` | `cupping_points` | free text |
| `beanconquerorId` | `config.uuid` | identity for re-import |

**Dropped** (surfaced in the preview banner so the user sees what was
skipped): the 10-axis cupping form, the SCA flavour wheel
(`cupped_flavor`), the self-roast curve (`bean_roast_information`),
EAN barcode, CO2e per kg, cost, QR / share code, attachments.

Lossy escape valve: `aromatics`, `bestDate`, `buyDate`, `cost`, `ean`
ride out into `bean.metadata.beanconqueror` so they round-trip
through localStorage even though Crema doesn't render them.

**Conflict resolution v1** per the coordinator: skip on duplicate
(case-insensitive `(roasterName, name, roastedOn)` triple). No
field-level merge UI.

### Phase 3 — Visualizer two-way sync

`web/src/lib/bean/visualizer-sync.ts` is the sync engine; the UI
hangs off Settings → Sharing via the new
`BeanSyncSection.svelte`.

- **Auth**: HTTP Basic per Visualizer's `/api` contract. Username +
  password persisted to `crema.beans.sync.v1` (separate from app
  settings — library-scoped). Plain because HTTP Basic needs the
  cleartext; the UI surfaces the storage warning in the help text.
- **Test Connection** issues a small read (`GET /coffee_bags?items=1`)
  to verify creds without probing the premium flag (which only a
  write can reveal).
- **Sync now** is a single coordinated run:
  1. Pull every remote roaster (paged). Reconcile by visualizer id,
     then by name. Create/update locally.
  2. Push every local roaster without a `visualizerId` via
     `POST /roasters`. Capture the remote id.
  3. Pull every remote bag (paged). Reconcile by visualizer id or by
     Crema id (stored in `metadata.crema.crema_id`). Remote wins per
     coordinator direction.
  4. Push every local bag without a `visualizerId` (POST). Push every
     local bag whose `updatedAt > lastSyncAt` (PATCH).
  5. Stamp `lastSyncAt`. Cache the premium flag.
- **Conflict resolution**: last-write-wins on `updated_at`. Remote
  wins per coordinator direction — Visualizer is the source-of-truth
  across devices. Field-level merge deferred (too complex for v1).
- **Field mapping**: `beanToWire` / `beanFromWire` shuttle the
  Crema-only fields (favourite, bag size, remaining, mix, decaf,
  rating, grinder, beanconqueror id, opened on, updated at) into the
  wire's `metadata.crema` block. Visualizer's `additionalProperties:
  true` makes this round-trip lossless.
- **Roast level**: Crema's 1..10 ↔ Visualizer's free-text via
  `roastLevelToWire` / `roastLevelFromWire` (Cinnamon → 1, Vienna →
  7, etc).
- **Premium gating**: bag / roaster *writes* are paywalled (€5/mo).
  The sync engine catches `HTTP 402 / 403` on the first write and
  flips the run into read-only mode for the remainder of the
  session. The flag is cached in `crema.beans.sync.v1` so we don't
  re-probe every sync. The UI surfaces this as "Connected — read-
  only (free tier)" with an upgrade link to visualizer.coffee.
- **Deletions**: `library.deleteBean(id)` / `deleteRoaster(id)` fire
  a best-effort `DELETE` against Visualizer (no-op when no
  `visualizerId` or premium-locked). Failure is logged and dropped.
- **Sync log**: each `runSync()` returns an aggregate `SyncResult`
  with a `log: SyncLogEntry[]` the UI surfaces as a recent-activity
  list (↑ pushed / ↓ pulled / · skipped / ✕ delete).

**Sync trigger v1**: manual "Sync now" button only. Auto-sync on app
launch is v1.1 (deferred).

---

## What was skipped

Per the coordinator's instructions + docs/28's "we kill" list:

- **OCR-from-photo** for the bag label (innovation #3 in docs/28).
  Heavy (WebAssembly OCR or Canvas + regex). Deferred to a follow-up;
  the UI has no `<input type="file" capture>` hook yet.
- **Photo storage** in IndexedDB. The Rust `Bean` carries
  `image_ref: Option<String>` and the TS type has `imageRef: string |
  null`, but no IDB blob store is set up. Photos are a TODO.
- **OAuth** flow against Visualizer. v1 uses HTTP Basic per docs/28
  §sync-model §phase-3. OAuth move-trigger: PWA store listing.
- **Auto-sync** on app launch + on every bean change. v1 is manual
  "Sync now" only — the docs/28 design called this out as v1.1.
- **Brew bulk-import** from Beanconqueror's `Brew[]` (docs/28
  §open-questions). The doc says "out of scope; own doc"; the
  importer dialog mentions this in passing.
- **Visualizer dedup against `canonical_coffee_bag_id`** catalogue
  (docs/28 §sync-model §open-questions). The hooks are present
  (`canonical_coffee_bag_id` flows through the wire shape) but the
  UX is deferred until Visualizer publishes their canonical list
  more visibly.
- **Compare-shots / multi-select bulk operations** on `/beans`. The
  page has single-bean actions; the docs/28 "long-press → select
  mode" idiom (from `ShotRow.svelte`) is a follow-up.
- **Field-level conflict-resolution UI** on Visualizer sync. Last-
  write-wins (remote wins) per coordinator direction. The merge UX
  is a v2 concern.
- **OCR roast-date extraction** (innovation #5 in docs/28). Deferred.
- **Freshness-band overlay on the LiveChart** (innovation #4 in
  docs/28). Deferred — the chart's overlay layer is non-trivial to
  re-render under shot conditions.

## TODOs left in code

- `web/src/lib/bean/visualizer-sync.ts` — `API_BASE` is hard-coded
  to `https://visualizer.coffee/api`. A future settings panel could
  let users override this for self-hosted or development.
- `web/src/lib/bean/store.svelte.ts` — `deleteBean` / `deleteRoaster`
  fire-and-forget the remote delete with a `console.warn` on failure.
  A future iteration could surface the failure in the sync activity
  log so the user knows their library is out of sync.
- `BeanContextCard.svelte` — the burn-down threshold uses a proxy
  18 g dose rather than the active profile's real dose. The card has
  no `params` reference (would couple it to the BrewParamState); a
  follow-up could thread the real dose through.

## Gates

All four green at commit time:

- `cd core && cargo test --workspace` — 24 new tests in `bean::tests`,
  no failures across the workspace.
- `cd core && cargo clippy --workspace --all-targets -- -D warnings` —
  clean.
- `cd web && pnpm check` — 0 errors, 0 warnings (480 files).
- `cd web && pnpm build` — production bundle builds clean.

## File map

**Phase 0 — core (Rust)**:
- `core/de1-domain/src/bean.rs` (+~480 lines, +12 tests)
- `core/de1-domain/src/lib.rs` (export the new types)

**Phase 1 — shell (TypeScript / Svelte)**:
- `web/src/lib/bean/model.ts` (rewritten — 380 lines)
- `web/src/lib/bean/store.svelte.ts` (rewritten — 290 lines)
- `web/src/lib/bean/index.ts` (rewritten — exports)
- `web/src/lib/components/beans/BeanEditor.svelte` (new)
- `web/src/lib/components/beans/BeanStrip.svelte` (new)
- `web/src/lib/components/brew/BeanContextCard.svelte` (rewritten on
  top of the library)
- `web/src/lib/components/brew/BrewDashboard.svelte` (host the
  BeanStrip)
- `web/src/lib/components/CremaSidebar.svelte` (Beans nav entry)
- `web/src/lib/history/model.ts` (extend `ShotBean` with `beanId`)
- `web/src/lib/state/app.svelte.ts` (snapshot the active bean,
  debit `remainingG`)
- `web/src/routes/beans/+page.svelte` (new — the `/beans` route)

**Phase 2 — import**:
- `web/src/lib/components/beans/BeanImportDialog.svelte` (new)

**Phase 3 — sync**:
- `web/src/lib/bean/visualizer-sync.ts` (new — sync engine)
- `web/src/lib/components/settings/sections/BeanSyncSection.svelte`
  (new — Settings → Sharing card)
- `web/src/lib/components/settings/sections/SharingSection.svelte`
  (host the BeanSyncSection)

## Branch state

Branch `bean-library` carries three commits, one per phase:

1. `core: Bean library types in de1-domain (Phase 0)`
2. `web: bean library shell — store, /beans route, brew picker (Phase 1)`
3. `web: Beanconqueror import + Visualizer two-way sync (Phase 2 + 3)`

The branch is not pushed; tests and gates pass locally. The handoff
is "merge when you're happy with it".

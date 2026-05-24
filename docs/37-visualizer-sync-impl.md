# 37 — Visualizer sync implementation

**Status:** 2026-05-24 — Phase 0..4 shipped on branch `visualizer-sync`. Implements docs/36 §1..§9 across `crema-web` + the Rust core, with Phase 5 (Web Workers) and the manual conflict UI deferred per docs/36 §11.

This is the implementation companion to **docs/36-visualizer-sync-plan.md** — that's the source of truth for behaviour and wire shape; this doc reports what shipped, where the wires are, and what's deferred.

---

## 1. What shipped

### Phase 0 — Foundation

| Item | Status | Where |
|---|---|---|
| `visualizerId` on Rust `StoredShot` | ✓ | `core/de1-domain/src/history.rs:117` |
| `deleted_at` on Rust `StoredShot` | ✓ | `core/de1-domain/src/history.rs:124` |
| `deleted_at` on Rust `Bean` | ✓ | `core/de1-domain/src/bean.rs:372` |
| `deleted_at` on Rust `Roaster` | ✓ | `core/de1-domain/src/bean.rs:518` |
| `visualizerId` / `deletedAt` on TS `StoredShot` | ✓ | `web/src/lib/history/model.ts:100` |
| `deletedAt` on TS `Bean` + `Roaster` | ✓ | `web/src/lib/bean/model.ts:99,178` |
| `coerceBean` / `coerceRoaster` reads `deletedAt` | ✓ | `web/src/lib/bean/model.ts:430,476` |
| Defensive history loader normalises new fields | ✓ | `web/src/lib/history/store.svelte.ts:loadShots` |
| `serde(default)` on every new Rust field | ✓ | all four sites |

Migration: existing localStorage records deserialise cleanly — every new field defaults to `null` / `None`, no schema bump needed. The Rust workspace tests stay green (`cargo test --workspace`).

### Phase 1 — Shot upload, Backup mode

| Item | Status | Where |
|---|---|---|
| `uploadShot(shot)` → POST | ✓ | `web/src/lib/visualizer/shot-sync.ts:185` |
| `deleteShot(visualizerId)` → DELETE | ✓ | `web/src/lib/visualizer/shot-sync.ts:210` |
| `patchShot(id, {rating, notes})` → PATCH | ✓ | `web/src/lib/visualizer/shot-sync.ts:227` |
| `pullShots(sinceMs)` → GET | ✓ | `web/src/lib/visualizer/shot-sync.ts:246` |
| `withFreshToken` integration | ✓ | `web/src/lib/visualizer/shot-sync.ts:call()` |
| Settings-aware payload (privacy / includeProfile / includeNotes) | ✓ | `web/src/lib/visualizer/shot-sync.ts:buildShotPayload` |
| `crema.visualizer.uploadQueue.v1` retry queue | ✓ | `web/src/lib/visualizer/upload-queue.ts` |
| Expo backoff 1s..60s, 3-attempt ceiling | ✓ | `upload-queue.ts:backoffMs` + `drainQueue` |
| Drain on launch / `online` / 5-min tick | ✓ | `upload-queue.ts:armQueueLifecycle` |
| Auto-upload wired into `ShotCompleted` | ✓ | `web/src/lib/state/app.svelte.ts:tryUploadShot` (~line 700) |
| Status pip (🟢🟡🔴⚪) on every History row | ✓ | `web/src/lib/components/history/ShotRow.svelte` |
| "Upload all (N)" toolbar button | ✓ | `web/src/routes/history/+page.svelte` |
| 4 preserved `visualizer*` settings wired | ✓ | `buildShotPayload` reads all four |

### Phase 2 — Shot pull + de-dup

| Item | Status | Where |
|---|---|---|
| `signatureForShot(...)` | ✓ | `web/src/lib/visualizer/shot-sync-signatures.ts` |
| `signatureForBean(...)` | ✓ | same |
| `signatureForRoaster(...)` | ✓ | same |
| `reconcileShots(local, remote)` pure planner | ✓ | same |
| `storedShotFromWire(remote)` ADD-branch builder | ✓ | same |
| Bean pull binds by signature before duplicating | ✓ | `web/src/lib/bean/visualizer-sync.ts:633` |
| Roaster pull binds by signature before duplicating | ✓ | `web/src/lib/bean/visualizer-sync.ts:506` |
| Soft-delete on `HistoryStore.delete` | ✓ | `web/src/lib/history/store.svelte.ts:delete` |
| Tombstone GC after remote DELETE succeeds | ✓ | `purgeTombstone` + queue handler |

### Phase 3 — Unified Settings UI

`web/src/lib/components/settings/sections/BeanSyncSection.svelte` is fully replaced. The visible card now contains:

- Master `Auto-sync` `StToggle`.
- Three per-entity rows (Beans / Roasters / Shots), each a `StSegment` over `[Off, Backup, Pull, Two-way]`, with row count + last-sync time in the sub-line.
- `Sync now` button — runs the bean+roaster `runSync`, then uploads every unsynced local shot, then drains the retry queue.
- Collapsed `Recent activity` log (last 20 entries) reading from `config.log` (the unified key `crema.visualizer.sync.v1`).
- Premium upgrade banner when a sync detects free-tier gating.

Per-entity premium gating: when `config.premium === false`, the Backup + Two-way segments for Beans / Roasters are click-ignored (the segment still renders so the user understands what's locked); Shots stay unrestricted because Visualizer's shot ingestion is free.

### Phase 4 — Polish

- Exponential backoff `1s → 2s → 4s → 8s → 16s → 32s → 60s` capped, 3 attempts max per queue entry — `upload-queue.ts:backoffMs`.
- `VisualizerError` classification (`upload-queue.ts:isRecoverable`):
  - `network` / `5xx` / `408` → retried.
  - `premium` (`402/403`) → dropped, surfaced in the log so the user upgrades.
  - `auth` (`401`) → dropped, `withFreshToken` already retried once internally.
  - Other `4xx` → dropped + logged.
- `online` / `offline`: `addEventListener('online')` triggers an immediate drain. The 5-min foreground tick early-exits when `navigator.onLine === false` (and when `document.hidden`).
- 5-min foreground auto-sync timer armed in the `CremaApp` constructor (`armQueueLifecycle`).

---

## 2. Concrete file:line refs

| Wire | File:line |
|---|---|
| Rust `StoredShot.visualizer_id` + `deleted_at` | `core/de1-domain/src/history.rs:117-128` |
| Rust `Bean.deleted_at` | `core/de1-domain/src/bean.rs:372-377` |
| Rust `Roaster.deleted_at` | `core/de1-domain/src/bean.rs:518-522` |
| TS `StoredShot` new fields | `web/src/lib/history/model.ts:100-115` |
| TS `Bean.deletedAt` | `web/src/lib/bean/model.ts:99-105` |
| `HistoryStore.delete` soft-delete branch | `web/src/lib/history/store.svelte.ts:delete` |
| `HistoryStore.bindVisualizerId` | `web/src/lib/history/store.svelte.ts` (after `delete`) |
| `signatureForShot` | `web/src/lib/visualizer/shot-sync-signatures.ts:signatureForShot` |
| `reconcileShots` pure planner | same file, `reconcileShots` |
| `uploadShot` POST entry | `web/src/lib/visualizer/shot-sync.ts:185` |
| `drainQueue` retry executor | `web/src/lib/visualizer/upload-queue.ts:drainQueue` |
| `armQueueLifecycle` (online + 5-min tick) | `web/src/lib/visualizer/upload-queue.ts:armQueueLifecycle` |
| Auto-upload trigger | `web/src/lib/state/app.svelte.ts:tryUploadShot` |
| `armQueueLifecycle()` call in constructor | `web/src/lib/state/app.svelte.ts:391` (end of `CremaApp.constructor`) |
| Status pip on row | `web/src/lib/components/history/ShotRow.svelte` (new `syncPip` prop) |
| Upload-all button | `web/src/routes/history/+page.svelte` (`uploadAll`) |
| Unified Sync settings card | `web/src/lib/components/settings/sections/BeanSyncSection.svelte` |
| Persistence keys | `sync-config.ts:SYNC_KEY` + `upload-queue.ts:QUEUE_KEY` |

Test coverage: `web/src/lib/visualizer/shot-sync.test.ts` runs 16 cases via `node:test`; invoke with `node --experimental-strip-types --experimental-detect-module --test src/lib/visualizer/shot-sync.test.ts`.

---

## 3. Gotchas hit

1. **Visualizer endpoint path.** docs/36 §5 lists `POST /api/v1/shots`, but the existing bean-sync layer hits the bare `/api` base, not `/api/v1`. The shot-sync module uses `/v1/shots` relative to `https://visualizer.coffee/api` to match the docs. If a real API call comes back 404 against that path, the shot-sync `API_BASE` is the one knob to change — but the architecture and field-mapping aren't affected.
2. **`exportStoredShotAsV2Json` requires wasm.** That meant the pure de-dup helpers had to live in a separate file (`shot-sync-signatures.ts`) so the `node:test` suite could import them without dragging in the wasm bridge. Same pattern as the existing `oauth.test.ts` setup. The signatures module is the single source; `shot-sync.ts` re-exports.
3. **`HistoryStore.all` semantics changed.** It used to return every row; it now filters tombstones (`deletedAt != null`). Existing call sites (history page, capture pruner) work fine because tombstones simply disappear from the UI. The sync layer reads `rawAll` to see tombstones it needs to push.
4. **Per-entity sync-config migration.** The legacy `crema.beans.sync.v1` shape is read once on first load and copied into the new `crema.visualizer.sync.v1` key, then left in place (docs/36 §7) so a roll-back doesn't lose the cached `premium` flag.
5. **`storedShotFromWire` produces stub shots.** Pulled shots have no telemetry series (Visualizer's pull-list response doesn't carry it), so the History row renders fine but the `ShotDetail` chart will show an empty curve. Per docs/36 §deferred §6 — "Profile reconstruction from shot JSON" is v2 work; the placeholder is intentional.
6. **The history page's "Upload all" runs serially.** Each `uploadShot` awaits before the next starts — keeps the rate-limit story simple. For 100+ unsynced shots a parallel batch would be faster; deferred behind a "Bandwidth on initial pull" follow-up.

---

## 4. Deferred items + rationale

Per the brief — explicit deferrals with reasoning:

| Item | Why deferred |
|---|---|
| **Web Workers** | docs/36 Phase 5 — performance is fine for v1; revisit only after measuring real-world cost. |
| **Manual conflict UI** (side-by-side diff modal) | docs/36 §4 — LWW handles the common cases; the diff modal is v2 once a real cross-device conflict is observed in the field. |
| **Profile reconstruction from pulled shot JSON** | Visualizer doesn't carry the full profile JSON in shot rows reliably; reconstruction is lossy. Stub shot shows "Profile not found locally" placeholder; docs/36 §10 question 1 is the canonical follow-up. |
| **Paginated initial pull** | Naive `GET /shots?since=0` for the first sync. Real cursor pagination kicks in only when `next_cursor` arrives. A 1000+ shot user is rare for v1; bandwidth tracking is a follow-up. |
| **Bean / roaster soft-delete UI** | The `deletedAt` field is in the model now (Rust + TS), but the current `BeanLibraryStore.deleteBean` still hard-deletes — the architecture is in place for a future toggle; we chose not to change the existing user-facing flow in this cut. |
| **shotCompleted webhook → upload race** | The webhook fires before `tryUploadShot`; no special ordering — race is acceptable because the webhook is independent. |

---

## 5. Verification checklist for the user

Manual flows to exercise before merging:

- [ ] Sign in to Visualizer via Settings → Sharing → Sign in. The new "Sync" card appears below the Visualizer connect card.
- [ ] Toggle Auto-sync off; pull a fresh shot. The pip should be ⚪ (not uploaded) and no upload should hit the network (check DevTools).
- [ ] Toggle Auto-sync on; pull another shot. Pip flickers 🟡 then becomes 🟢. The History row's tooltip on the pip explains the state.
- [ ] In Settings → Sharing → Sync, click "Show log". Each completed upload appears as a `↑ Shot "<profile>" — <time>` row.
- [ ] Toggle Shots direction to Off. Pull a shot. Pip stays ⚪. Toggle back to Backup; click "Upload all (1)" in `/history` header — should upload the held-back shot.
- [ ] Open DevTools → Network, throttle to offline, pull a shot. Pip should be 🟡 (pending). Restore connectivity; within 5 min (or immediately on `online` event) the queue drains and the pip turns 🟢.
- [ ] Open `localStorage` and inspect `crema.visualizer.sync.v1` (per-entity direction + log) and `crema.visualizer.uploadQueue.v1` (retry queue). Both should round-trip correctly across reloads.
- [ ] Delete a synced shot from the History detail panel. The row disappears from the list but `crema.history.v2` still carries a tombstone with `deletedAt` set. Run "Sync now" — the tombstone should be DELETEd remotely + GC'd locally (the queue handler does the GC after the remote 204).
- [ ] On a fresh device (or with localStorage cleared), sign in and click "Sync now". Remote shots whose signatures match local rows with `visualizerId === null` should bind (no duplicate created); the rest should appear as stub shots with empty telemetry.

---

## 5. OpenAPI grounding (2026-05-24, branch `visualizer-openapi-grounding`)

The visualizer.coffee team published their OpenAPI spec mid-stream. The
previous cut guessed several endpoints + query params from observation;
this section is the catalogue of corrections applied once the spec
landed. The vendored snapshot lives at
`web/src/lib/visualizer/openapi.json` (the source-of-truth still lives
at the user's `~/code/visualizer-api.json`; we vendor it so the build
is hermetic).

### 5.1 Codegen setup

We use [`openapi-typescript`](https://github.com/openapi-ts/openapi-typescript),
the most widely adopted TS generator for OpenAPI 3.x. It emits a single
`paths` + `components` type tree — not classes, just types — into
`web/src/lib/visualizer/openapi.d.ts`. Crema's code imports the spec
types via:

```ts
import type { components } from '$lib/visualizer/openapi';
type ShotSummary = components['schemas']['ShotSummary'];
type CoffeeBagDetail = components['schemas']['CoffeeBagDetail'];
```

To refresh after Visualizer updates their spec: drop the new JSON into
`web/src/lib/visualizer/openapi.json` and run `pnpm openapi` (added to
`web/package.json`). The generated `.d.ts` is committed so a fresh
checkout doesn't need the codegen tool to type-check.

### 5.2 Corrections applied

| Endpoint | Bug | Fix |
|---|---|---|
| `GET /shots` | sent `?per_page=` + `?since=<ms>`; expected `{data, next_cursor}` response | Now sends `?items=N&page=P&sort=updated_at`; reads `{data: ShotSummary[], paging: {count,page,limit,pages}}`; stops walking when a summary's `updated_at` (unix seconds) is older than the local cursor (converted to seconds from ms at the boundary). |
| Shot details | treated list-response rows as full shots (they only carry `{id, clock, updated_at}` per `ShotSummary`) | Added `fetchShotDetail(id)` → `GET /shots/{id}` → `ShotDetail`. `pullAllShotsSince` fetches detail per new summary. |
| `PATCH /shots/{id}` | sent bare `{rating, notes}` body | Wraps in `{shot: {espresso_enjoyment, private_notes}}` envelope per `ShotUpdateRequest`. Crema's small-integer `rating` clamps to the spec's 0..15 score range. |
| `POST /roasters`, `PATCH /roasters/{id}` | sent bare body — silently dropped server-side | Wraps in `{roaster: {...}}` envelope per `RoasterWriteRequest`. |
| `POST /coffee_bags`, `PATCH /coffee_bags/{id}` | sent bare body — silently dropped server-side | Wraps in `{coffee_bag: {...}}` envelope per `CoffeeBagWriteRequest`. |
| `GET /roasters`, `GET /coffee_bags` | walked pages via `data.length < 100` heuristic | Walks `paging.pages` instead. Param `items=100` matches the spec name. |
| `WireShot.updated_at` | typed `string | null` (we'd planned for ISO-8601) | Now `updated_at_ms: number | null`, converted from the spec's unix-seconds `updated_at` at the wire boundary. |
| Premium upgrade link | pointed at `/upgrade` (404) | Now points at `/premium` (the canonical landing per the spec doc). |

### 5.3 Unix seconds vs milliseconds

Visualizer wires unix **seconds** on `ShotSummary.clock`, `ShotSummary.updated_at`, `DefaultShotDetail.updated_at`, and `DecentUploadPayload.timestamp`. Crema's `StoredShot.completedAt` and `bean.updatedAt` are unix **milliseconds**. The conversion happens at exactly two sites:

1. `wireShotFromDetail` in `shot-sync.ts`: `summary.clock * 1000`, `summary.updated_at * 1000`, `detail.duration * 1000`.
2. `pullAllShotsSince` cursor comparison: `cursorSec = Math.floor(sinceMs / 1000)` before comparing against `summary.updated_at`.

If a future bug reports "shots from before some date stop coming back," the cursor-conversion is the first thing to inspect.

### 5.4 Premium probing limitations

The spec confirms `/me` exposes **no premium flag** — the only signal is a 403 on a write requiring the `write` scope under premium. The current behavior:

- `premium === null` (not yet probed) — let the user click Sync; we discover via the first 403.
- `premium === false` (probed; free tier) — the Beans / Roasters direction segments still render the four options, but the Backup / Two-way segments are click-ignored. A lock icon + tooltip + inline "Visualizer Premium required for push." link explains why.
- `premium === true` (probed; pushes succeeded) — no UI change.

When a sync run downshifts to read-only because of a 403, the activity log gets one clear header entry:

> Premium required — beans + roasters disabled from push. Upgrade at visualizer.coffee/premium.

…followed by per-row "premium required" skip lines for the bags / roasters that didn't push.

The 403 cache lives in `crema.beans.sync.v1.premium` (localStorage). A future Visualizer feature that downgrades a user back to free tier would leave a stale `true` cached — for now we only re-probe on explicit "Disconnect & sign back in".

---

## 6. Test coverage

- 16 node:test cases in `web/src/lib/visualizer/shot-sync.test.ts` covering signatures + reconcile + storedShotFromWire.
- 233 unchanged Rust tests in `cargo test --workspace`.
- `pnpm check` clean (513 files, 0 errors, 0 warnings).
- `pnpm build` clean.
- `cargo clippy --workspace --all-targets -- -D warnings` clean.

Integration tests against a mocked fetch are deferred until the next sprint (they'd want msw or similar; the current tests cover the planning layer, which is the part most likely to regress on a refactor).

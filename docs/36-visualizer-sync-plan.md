# 36 — Visualizer sync plan

**Status:** 2026-05-24 design doc. Covers shots + beans + roasters across one-way and two-way modes, with explicit de-dup detection.

Crema's local entities live in `localStorage`. Visualizer is the canonical online surface for backup + cross-device + community sharing. This doc lays out the full sync architecture so the per-entity work (already partially shipped for beans + roasters, pending for shots) lands against a coherent foundation.

---

## 1. Scope

### Entities synced

| Entity | Local key | Visualizer endpoint | Status today |
|---|---|---|---|
| Bean | `crema.beans.v1` | `/api/v1/coffee_bags` | Pulled + pushed (premium gate) |
| Roaster | `crema.roasters.v1` | `/api/v1/roasters` | Pulled + pushed (premium gate) |
| Shot | `crema.history.v1` | `/api/v1/shots` (v2 JSON body) | **Not synced** — this doc adds it |

### Entities NOT synced (out of scope)

- **Profiles** — Visualizer doesn't have a first-class profile library; profile JSON rides along inside each shot's payload. Cross-device profile sync is a separate problem (Google Drive backup #74 handles this).
- **Settings, maintenance counters** — strictly per-device.
- **Replay captures, photos** — large blobs; Drive backup (v2) handles these.

### What "sync" means

Two distinct user goals — they overlap but aren't the same:

1. **Backup** — write to the cloud so a local cache wipe doesn't lose data. Direction: push-only. Reads are rare (only after a restore event).
2. **Cross-device** — edit on phone, see on desktop. Direction: two-way, with conflict resolution.

This plan supports both by exposing a per-direction toggle per entity type.

---

## 2. Direction modes

Three settings per entity, exposed as a `StSegmented` in Settings → Sharing → "Visualizer sync":

| Mode | Push | Pull | Use case |
|---|---|---|---|
| **Off** | ✗ | ✗ | Disable for a single entity (e.g., backup beans but not shots) |
| **Backup** | ✓ | ✗ | Push local to remote only; remote treated as write-only archive |
| **Pull only** | ✗ | ✓ | Crema is read-only mirror of Visualizer (e.g., browsing your remote library on a fresh install before you decide to push) |
| **Two-way** | ✓ | ✓ | Full sync — edits on one device propagate; conflict resolution applies |

Defaults: Two-way for beans + roasters (matches today's behavior); Backup for shots (most users don't edit shots remotely).

UI shape per entity row:
```
┌─────────────────────────────────────────────────────────────┐
│ Beans          Off · Backup · Pull · Two-way    Last sync: 3m ago │
│ Roasters       Off · Backup · Pull · Two-way    Last sync: 3m ago │
│ Shots          Off · Backup · Pull · Two-way    Last sync: never  │
└─────────────────────────────────────────────────────────────┘
```

Plus a master "Auto-sync" toggle that controls whether sync fires on changes or only on explicit "Sync now."

---

## 3. De-duplication strategy

The hardest part. A bean created on phone before sign-in, and the same bean created on desktop before sign-in, both get unique local UUIDs. When the user signs in on both devices, naive two-way sync uploads two copies. De-dup needs to detect "these are the same thing."

### Per-entity de-dup keys

| Entity | Primary key | De-dup signature |
|---|---|---|
| Bean | `id` (crema-minted UUID `bean:<uuid>`) | Hash of `(name, roasterName, roastedOn)` |
| Roaster | `id` (`roaster:<uuid>`) | Normalized name (lowercase, trim, strip whitespace/punct) |
| Shot | `id` (`shot:<uuid>`) | Hash of `(startedAtMs, durationMs, profileId, finalWeightG)` — shots are inherently unique by time |

### Sync identity vs local identity

Each local entity carries an **optional remote pointer**: `visualizerId: string | null`.

- Before first sync: `visualizerId = null`
- After first push: `visualizerId = <remote-id>` (persisted to localStorage)
- After first pull: same — the entity is born with `visualizerId` set
- On subsequent sync: identity is matched by `visualizerId`, not by `id`

This means **two devices can have different local `id`s but the same `visualizerId`** — fine. The local `id` is just for in-memory + URL purposes.

### De-dup flow

When pulling:
1. For each remote entity, check `visualizerId` against local entities.
2. If found, last-write-wins on `updatedAt` (already implemented for beans + roasters per docs/28).
3. If not found, compute the **de-dup signature** and look for a local entity matching the signature with `visualizerId === null`.
4. If signature match found → bind: set `localEntity.visualizerId = remote.id`. **No duplicate created.** Adopt remote's `updatedAt` as a tiebreaker.
5. If no signature match → create new local entity from remote.

When pushing:
1. For each local entity with `visualizerId === null`, send a POST to create. Set the returned remote id as `visualizerId` and persist.
2. For each local entity with `visualizerId !== null` and `updatedAt > lastSyncAt` (local dirty), send a PUT.
3. For each local entity marked `deletedAt !== null` AND `visualizerId !== null`, send a DELETE then remove locally.

### Soft delete

Add `deletedAt: number | null` to bean, roaster, shot. When the user deletes:
- Set `deletedAt = Date.now()` (instead of splicing the array)
- UI filters out `deletedAt != null`
- Next sync push: DELETE remote, then garbage-collect the local row after the DELETE succeeds (or after 30 days, whichever is sooner)

Soft delete is required for cross-device propagation: if device A hard-deletes locally and device B has the same row, two-way sync would re-create from B unless A had a tombstone to push.

### De-dup banner

When the bean library page detects two beans with the same de-dup signature (typical post-sync: bean had `visualizerId=null` and was just bound to a remote that another device already pushed), surface a "Merge duplicates" banner — same pattern as the existing roaster dedup banner shipped in PR #70. User one-clicks → both rows fold into the one with the longer history (more shots, more recent updatedAt).

---

## 4. Conflict resolution

When local and remote both modify the same entity between syncs:

| Strategy | Description | Used for |
|---|---|---|
| **Last-write-wins (LWW)** | Compare `updatedAt`; newer wins | Default for beans + roasters (current behavior) |
| **Remote-wins** | Remote always wins (Visualizer is canonical) | Optional per-entity setting |
| **Local-wins** | Local always wins | Optional per-entity setting |
| **Manual** | Surface a conflict UI listing both sides; user picks | Shots only — they're history records, hard to auto-merge |

For v1, ship LWW only. Field-level merging is too complex and rarely needed for the data Crema syncs.

### Conflict surface UI (future v2)

When manual mode is enabled and a conflict is detected, the sync log entry becomes clickable → opens a side-by-side diff modal:

```
┌─────────────────────────┬─────────────────────────┐
│ Local (your phone)      │ Remote (visualizer)     │
│ Name: Geisha            │ Name: Panama Geisha     │
│ Rating: 4               │ Rating: 5               │
│ Updated: 2m ago         │ Updated: 5m ago         │
└─────────────────────────┴─────────────────────────┘
  [Keep local]    [Keep remote]    [Merge fields…]
```

---

## 5. Shot sync — the new piece

### Wire format

Use the v2 JSON shape Crema already produces via `core/de1-domain/src/history_export.rs` (wired in `web/src/lib/history/v2-export.ts`). It matches what reaprime / Visualizer expect. Field-level checklist:

- `clock` (epoch ms at shot start)
- `duration_ms`
- `profile` (full JSON — Visualizer extracts what it needs)
- `bean` snapshot (denormalized `ShotBean` per docs/28 — name, roaster, roastedOn, roastLevel)
- `data` (the telemetry samples — pressure, flow, weight, temps)
- `annotations` (drink TDS, EY, enjoyment if user sets them — optional)
- `metadata.crema` (Crema-only escape valve: `localId`, app version, device label)

### Endpoints

- `POST /api/v1/shots` — create. Returns shot id + URL.
- `GET /api/v1/shots?since=<unix-ms>` — incremental pull (newer than cursor).
- `PATCH /api/v1/shots/<id>` — update annotations (notes, enjoyment, TDS). Telemetry is immutable per Visualizer's model.
- `DELETE /api/v1/shots/<id>` — delete.

### Per-shot upload settings

The settings audit found 4 visualizer\* fields preserved-but-unused in `DEFAULT_SETTINGS`. Wire them now:

- `visualizerAutoUpload: boolean` (default `true` when shot mode = Backup or Two-way; `false` when Off / Pull-only)
- `visualizerPrivacy: 'public' | 'unlisted' | 'private'` (default `unlisted`)
- `visualizerIncludeProfile: boolean` (default `true`)
- `visualizerIncludeNotes: boolean` (default `true`)

### Auto-upload trigger

On `ShotCompleted` event in `app.svelte.ts`, if shot sync direction ∈ {Backup, Two-way} AND `visualizerAutoUpload`, push to Visualizer. Fire-and-forget with retry queue (persisted in `crema.visualizer.uploadQueue.v1`); retries on next sync cycle.

### Status surface

Per-history-row pip:
- 🟢 Uploaded (`visualizerId !== null`)
- 🟡 Pending (in upload queue)
- 🔴 Failed (last attempt errored; tooltip shows reason)
- ⚪ Local-only (mode is Off or Pull-only)

Bulk "Upload all" button on `/history` for filling the gap on first sync.

---

## 6. Sync state machine

```
              ┌────────┐
              │  idle  │ ←──────────┐
              └───┬────┘            │
                  │ user clicks     │
                  │ Sync now,       │
                  │ auto-sync tick, │
                  │ or token        │
                  │ refresh         │
                  ▼                 │
            ┌──────────┐            │
            │ syncing  │ ───────────┤
            └─┬────────┘  success   │
              │ error               │
              ▼                     │
        ┌──────────┐                │
        │ retrying │ ── eventually ─┘
        └──────────┘
```

Per entity-type, sync stages:
1. **Compute the diff** — local entities tagged with `dirty` (changed since last sync), `new` (no visualizerId yet), `tombstone` (deletedAt set)
2. **Pull** if direction includes pull — `GET /coffee_bags?since=<lastSyncAt>` etc. Reconcile via the de-dup flow above.
3. **Push** if direction includes push — POST new, PATCH dirty, DELETE tombstones.
4. **Update lastSyncAt** in settings on full success.

### Retry / backoff

- Transient errors (network, 502, 503): exponential backoff (1s, 2s, 4s, 8s, max 60s) up to 3 attempts.
- 401 (token expired): refresh token, retry once. If still 401, prompt re-auth.
- 402 / 403 (premium gate): cache premium-locked flag in `crema.beans.sync.v1`, stop trying pushes until user upgrades.
- 4xx other: log + drop from queue.

### Auto-sync cadence

- On app launch, if signed in + last sync > 60s ago, fire a background sync.
- On shot completion, if shot mode includes push, fire shot upload only.
- Every 5 min while app is in foreground.
- Never while offline (`navigator.onLine === false`); resume on `online` event.

---

## 7. Persistence shape

New localStorage keys:

| Key | Shape | Purpose |
|---|---|---|
| `crema.visualizer.sync.v1` | `{ direction: { beans: Mode, roasters: Mode, shots: Mode }, autoSync: boolean, lastSyncAt: { beans: number, roasters: number, shots: number }, premium: boolean \| null }` | Per-entity sync config + state |
| `crema.visualizer.uploadQueue.v1` | `{ entries: { entity: 'shot' \| 'bean' \| 'roaster', id: string, op: 'create' \| 'update' \| 'delete', attempts: number, lastError?: string }[] }` | Retry queue |
| `crema.visualizer.deletedShots.v1` | `{ id: string, deletedAt: number }[]` | Tombstones for shots (beans + roasters carry `deletedAt` inline) |

Existing `crema.beans.sync.v1` from the bean library work stays; some fields move to `crema.visualizer.sync.v1` over time.

Migration: on first load, read old `crema.beans.sync.v1` shape and copy into the new key. Tombstone the old key after a release.

---

## 8. UI surfaces

### Settings → Sharing → "Visualizer sync"

(Below the existing Visualizer connection card.)

A new `StGroup` "Sync" with:

- **Auto-sync** toggle (master switch)
- Per-entity rows: Beans / Roasters / Shots → each a `StSegmented` for direction
- **Sync now** button (single-shot manual sync)
- **Sync log** (collapsed by default; last 20 entries with timestamps)
- **Conflict log** (only when manual conflicts are pending; click to resolve)

### `/history` page

- Status pip on every row
- Bulk "Upload all" button in the toolbar when ≥1 shot has no `visualizerId`

### `/beans` page

- Last-sync indicator + a "Sync now" mini-button in the header (mirrors today's `/history`)

### Brew dashboard

- Subtle status pill below the Coffee button: when shot mode is push-enabled, after a shot completes, show "Uploading…" → "Uploaded" → "Failed (retry)" briefly

---

## 9. Implementation phases

**Phase 0 — Foundation** (now): formalize the `visualizerId` field on Bean + Roaster (already there) + add to Shot (NEW). Add `deletedAt` everywhere. Migration shims.

**Phase 1 — Shot upload (Backup mode)** — task #73. POST shots on completion. Settings UI for the 4 visualizer\* fields. Retry queue. Status pips on history rows. **No pull. No two-way.** Closes the "I want my shots backed up" use case.

**Phase 2 — Shot two-way** — Pull shots + manual conflict resolution. Direction selector per entity. De-dup detection on first-ever sync.

**Phase 3 — Unified UI** — Replace the existing bean-sync UI with the per-entity matrix described above. One sync log across all three entity types.

**Phase 4 — Polish** — Auto-sync cadence, online/offline awareness, retry queue surfacing, conflict log UI.

**Phase 5 (optional)** — Web Workers for sync so the main thread isn't blocked during large initial pulls.

### Effort

| Phase | Effort | Blocker |
|---|---|---|
| 0 | ~2h | None |
| 1 | ~1d | None (just dispatch task #73) |
| 2 | ~2-3d | Phase 1 |
| 3 | ~1d | Phase 2 |
| 4 | ~1d | Phase 3 |
| 5 | ~half-day | Phases 1-3 measured for actual perf cost |

Total to "full two-way sync for everything with de-dup + conflict UI": ~6-7 dev-days.

---

## 10. Open questions

1. **Profile sync** — Visualizer doesn't have a first-class profile store. Profiles ride on shots. If a user creates a profile on device A and brews 5 shots, then signs in on device B and pulls — they get 5 shots referencing a profile that doesn't exist locally. Options:
   - Reconstruct profile from the shot's embedded JSON (lossy if Visualizer trimmed any fields)
   - Treat profiles as device-local; live with the missing-profile placeholder on B
   - Add Drive backup for profiles (task #74)
   Default v2.3 behavior: option 2 (placeholder), with reconstruct as a "Recover profile from shot" action on the placeholder.

2. **Premium gate granularity** — Visualizer's premium gates bag/roaster CRUD but shots are free. If the user is free-tier:
   - Beans / roasters: stuck on Pull-only (current behavior)
   - Shots: can Backup or Two-way (free)
   The direction selector should reflect this — disable the push side of bean/roaster modes when `premium === false`.

3. **Soft-delete retention window** — keeping tombstones forever bloats localStorage. 30 days feels right (every device should sync within 30 days). 90 days is safer but doubles storage. Pick after measuring real-world tombstone counts.

4. **Bandwidth on initial pull** — if a user has 1,000 shots on Visualizer and signs in on a fresh device, pulling all of them eats time + memory. Paginate `GET /shots?page=N&per=50` and stream into IndexedDB / localStorage incrementally. Show a progress bar.

5. **Multi-device tomb races** — phone deletes bean → tombstone pushed → desktop pulls deletion → bean disappears on desktop, fine. But: phone deletes → desktop modifies before pulling the delete → user's intent is unclear. LWW handles this (desktop's `updatedAt > deletedAt` ⇒ keep the modified, ignore tombstone). Document this corner.

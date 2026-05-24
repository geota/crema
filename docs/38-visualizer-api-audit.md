# 38 — Visualizer API audit: features we don't use yet

**Status:** 2026-05-24 against the OpenAPI spec at repo root (`visualizer-openapi.json`).

The spec exposes 14 operations. Crema currently uses 6. This doc walks the rest + scores each for "worth wiring" based on user-visible value vs implementation cost.

## Scope tally

| Used today | Op |
|---|---|
| ✓ | `GET /me` — account identity |
| ✓ | `POST /shots/upload` — push a shot |
| ✓ | `GET /shots` — list (now pagination-correct per PR #76) |
| ✓ | `PATCH /shots/{id}` — update shot |
| ✓ | `DELETE /shots/{id}` — delete shot |
| ✓ | `GET /coffee_bags` + `POST` + `GET /{id}` + `PATCH /{id}` + `DELETE /{id}` |
| ✓ | `GET /roasters` + `POST` + `GET /{id}` + `PATCH /{id}` + `DELETE /{id}` |

| **Unused** | Op |
|---|---|
| ✗ | `GET /shots/shared?code=…` — resolve a public share-link |
| ✗ | `GET /shots/{id}/download` — raw shot file (alias of GET) |
| ✗ | `GET /shots/{id}/profile` — download just the profile JSON |
| ✗ | `GET /roasters/{roaster_id}/coffee_bags{,/{id}}` — legacy nested index (deprecated; skip) |

Plus dozens of **unused fields** on existing PATCH/POST endpoints.

---

## High-value wires we should add

### 1. Link shots to coffee bags after upload (`PATCH /shots/{id} { coffee_bag_id }`)

**What it does:** every shot you upload becomes browsable via the bag it used. Visualizer shows "all shots from this Yirgacheffe" — a key feature of the product.

**Crema state:** we record `bean.visualizerId` and `shotBean` snapshot on every shot, but never wire them together server-side. After a successful `POST /shots/upload`, if `shot.bean?.visualizerId != null`, fire `PATCH /shots/{id} { shot: { coffee_bag_id } }`.

**Cost:** ~20 lines in `shot-sync.ts`. **Ship it** — biggest visible win per line of code in this audit.

### 2. Cupping scores on shot PATCH

**What it does:** `PATCH /shots/{id}` accepts 8 SCA cupping dimensions (fragrance, aroma, flavor, aftertaste, acidity, bitterness, sweetness, mouthfeel), each `0..15`.

**Crema state:** we have a single `rating: 0..5` per shot. Map ours to one of theirs (probably `flavor` since it's the central one) or expose a "Cupping notes" detail view that opens the 8 sliders for power users.

**Cost:** depends. Just mapping `rating → flavor` on PATCH is ~5 lines. A full cupping-form UI is ~half-day. **Recommend: ship the simple mapping now**; full cupping form is power-user TODO.

### 3. `private_notes` on shot

**What it does:** shot notes that don't show up publicly when the user's profile is public.

**Crema state:** we have one `notes` field. Map ours → `private_notes` so notes don't leak when the user toggles their account public. Lossless for the more-private interpretation.

**Cost:** ~3 lines. **Ship it.**

### 4. Public-profile toggle in Settings (`MeResponse.public`)

**What it does:** at the *user* level, Visualizer accounts are either public or private. Public = your shots are browsable at `/users/{id}`. Private = only shareable individually via `/shots/shared?code=…` codes.

**Crema state:** we read `account.public` but ignore it.

**Gotcha:** there is **no `PATCH /me` endpoint** in the spec. The toggle is settable on visualizer.coffee's web UI, not via API. Crema can only **display** the user's current public/private state (in the connected card's meta line) + link out to the profile-settings page.

**Cost:** ~5 lines to surface the state + link. **Ship it as a meta-line annotation** ("Connected · Jane · public profile" or "private").

### 5. `coffee_bag.metadata: object` round-trip (already partially done)

**What it does:** Coffee bags have a `metadata` field for arbitrary JSON. Crema's bean has Crema-only fields (`bagSizeG`, `remainingG`, `tags`, `mix`, `decaf`, etc.) that aren't in `CoffeeBagDetail`. Per docs/28 §design-decisions, we ride those in `metadata.crema.*`.

**Crema state:** wired for some fields, not all. The PR #76 codegen pass should make this visible — `CoffeeBagDetail.metadata` is typed as `object`, so we're free to slot anything we want inside.

**Cost:** ~half day to audit + close the gaps. Probably a follow-up task.

### 6. `RoasterDetail.image_url` already wired in PR #70

Confirmed in PR #70. Roasters' `image_url` rides directly on the wire.

---

## Medium-value wires

### 7. `GET /shots/shared?code=…` — share-link resolution

**What it does:** Visualizer's "share this shot" UI generates a code; the URL `/shots/shared?code=ABC123` resolves to a (public) shot detail. Crema could:
- **Generate** share links — but the spec has no "create share code" endpoint. The code is created on Visualizer's web side. Skip.
- **Consume** share links — a "Compare against a Visualizer shot" feature: paste a share code, Crema fetches the shot, overlays its telemetry against your latest shot. Cool feature for community shot diff.

**Cost:** ~1 day for the consume side (fetch + overlay UI). **Defer — power-user feature.**

### 8. Tag list on shots (`PATCH /shots/{id} { tag_list: string[] }`)

**What it does:** each shot can carry a list of tags. Different from bean tags or profile tags.

**Crema state:** we don't have shot-level tags. Could add by importing the bean's tags onto the shot at upload time. Free-form tagging UI is its own feature.

**Cost:** ~1 day for the UI. Without UI, ~5 lines to auto-tag shots from their bean.tags. **Auto-tag now (cheap); explicit tag UI later.**

### 9. `barista` field on shot PATCH

**What it does:** identifies who pulled the shot. Useful for multi-user bars; not for solo home users.

**Crema state:** no concept of `barista`. Could map to `account.name` for a single-user app, but that's redundant with the existing user attribution.

**Cost:** ~3 lines. **Skip unless users ask** — feels like noise for solo users.

### 10. `/shots/{id}/profile` standalone profile download

**What it does:** GET just the profile JSON from a shot. Public, no auth.

**Crema state:** when pulling a shot whose profile isn't in our local library, today we fall back to "Profile not found locally" per docs/37. With this endpoint we could **reconstruct the profile** from a pulled shot.

**Cost:** ~1 day to wire reconstruction + import. **Defer per docs/36 §10.1** — the reconstructed profile is lossy and the user might prefer the "missing profile" placeholder.

---

## Bag-side gaps to close

`CoffeeBagDetail` exposes 21 fields Crema partially uses. Quick scan:

| Bag field | Crema state | Note |
|---|---|---|
| `name`, `roast_date`, `roast_level`, `country`, `region`, `farm`, `farmer`, `variety`, `elevation`, `processing`, `harvest_time`, `quality_score`, `tasting_notes`, `place_of_purchase`, `url`, `notes`, `image_url`, `archived_at` | ✅ all wired | Confirmed in `BeanOrigin` + `Bean` |
| `frozen_date`, `defrosted_date` | ✅ on `Bean` as `frozenOn`/`defrostedOn` | Name diff: `_date` (wire) vs `On` (Crema). Map in toWire. |
| `metadata` | partial | See item 5 |

Should be a clean field-map check — assign to a quick audit task.

---

## Roaster-side: fully covered

`RoasterDetail` has `id, name, website, image_url`. All wired in PR #70. Plus Crema-only `city`/`country`/`notes`/`canonicalRoasterId` ride in `metadata.crema.*` losslessly.

---

## Recommendations (priority-ordered)

| # | Wire | Cost | Ship now? |
|---|---|---|---|
| 1 | shot→bag link (`PATCH coffee_bag_id`) | ~20 lines | ✅ Yes |
| 2 | rating → `flavor` mapping | ~5 lines | ✅ Yes |
| 3 | shot.notes → `private_notes` | ~3 lines | ✅ Yes |
| 4 | display user-public-vs-private | ~5 lines | ✅ Yes |
| 5 | bean tags → `tag_list` on shot | ~5 lines | ✅ Yes (auto-tag) |
| 6 | bag `metadata` field audit | ~half day | TODO |
| 7 | bag `frozen_date`/`defrosted_date` mapping verification | ~10 min | TODO (likely fine; verify) |
| 8 | Full cupping form (8 sliders) | ~half day | TODO (power-user) |
| 9 | Share-code consume / overlay | ~1 day | Defer |
| 10 | Profile reconstruction from pulled shot | ~1 day | Defer (lossy per docs/36) |

Items 1-5 bundle into a single PR (~60 lines + a few minutes thinking about edge cases). High visible-value/effort ratio.

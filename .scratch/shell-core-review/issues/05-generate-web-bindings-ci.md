# 05 — Generate web `crema-core.ts` from `core/bindings/` + CI freshness check

- **Status:** done (staleness + CI fixed; bean/wire-shot dedup deferred — see Comments)
- **Severity:** P1
- **Area:** Web · Core (WASM bindings)
- **Punchlist:** T1-05 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem

`web/src/lib/core/crema-core.ts` (~1043 lines) is a hand-maintained stale copy of generated `core/bindings/crema-core.ts` (~1602 lines), missing 13 types (`Bean`, `BeanMix`, `BeanOrigin`, `BeanRoastType`, `Roaster`, `ShotBean`, `WeightUnit`, `MaintenanceState`, `MaintenanceReadout`, `LocalShotRef`, `ReplayMeta`, `ReplayMetaBean`, `WireShot`, `De1Uuids`). Several are re-declared by hand: `maintenance/store.svelte.ts:55,153`, `visualizer/shot-sync-signatures.ts:48`, `bean/model.ts`, `history/model.ts`. A new Rust field silently won't appear web-side.

## Fix

- Make `web/src/lib/core/crema-core.ts` re-export (or be generated from) `core/bindings/crema-core.ts`.
- Delete the hand re-declarations in `maintenance/store.svelte.ts`, `visualizer/shot-sync-signatures.ts`, `bean/model.ts`, `history/model.ts`.
- Add a CI step that fails if the generated binding is newer than the checked-in copy.

## Acceptance / Verify

CI fails on a stale binding; `MaintenanceState`/`WireShot`/`Bean` imported from one place.

## Touched files

- `web/src/lib/core/crema-core.ts` — replace with re-export or generated file (~1043 lines → route to `core/bindings/`)
- `core/bindings/crema-core.ts` — source of truth (~1602 lines)
- `web/src/lib/maintenance/store.svelte.ts:55,153` — delete hand re-declarations
- `web/src/lib/visualizer/shot-sync-signatures.ts:48` — delete hand re-declaration
- `web/src/lib/bean/model.ts` — delete hand re-declaration
- `web/src/lib/history/model.ts` — delete hand re-declaration
- CI config (`.github/workflows/` or equivalent) — add freshness check step

## Comments
<!-- triage + progress notes append below -->

### 2026-06-13 — staleness + CI done; type dedup partly deferred

**Done (the P1 — "a new Rust field silently won't appear web-side"):**

- `web/src/lib/core/crema-core.ts` is now a *generated artifact*: regenerated it
  from `core/bindings/crema-core.ts` (1043 → 1602 lines), gaining all 14
  previously-missing types (`Bean`, `BeanMix`, …, `WireShot`, `De1Uuids`).
  Confirmed web's old file was a strict stale **subset** (no divergent types).
- `core/generate-bindings.sh` now `cp`s the TS output into the web shell, so one
  generation keeps both in sync.
- **CI freshness check** (`.github/workflows/ci.yml`, `rust` job): installs
  pinned `typeshare-cli 1.13.4`, re-runs `generate-bindings.sh`, and
  `git diff --exit-code core/bindings web/src/lib/core/crema-core.ts` — a stale
  binding (Rust type changed without regen, or web copy out of sync) fails CI.
  Mirrors the existing `gen-builtin-ids` idempotency gate. (Can't run GH Actions
  locally; step is modelled on that established pattern.)
- **Deduped `MaintenanceState` / `MaintenanceReadout`**: field-identical to the
  generated types, so `maintenance/store.svelte.ts` now imports them from
  `$lib/core/crema-core` (index re-exports from there). `npm run check`: 0 errors.

**Deferred — NOT a stale copy, a deliberate web idiom (finding):** the remaining
hand declarations are *not* outdated duplicates; they re-shape the core types to
web-idiomatic TS, so deduping them is an enum/optional **migration**, not a sync:

- `bean/model.ts`: `BeanMix`/`BeanRoastType` are string unions (`'single' |
  'blend'`) where typeshare emits TS `enum`s; `Bean`/`Roaster`/`BeanOrigin` build
  on those. Switching means every `'single'` literal → `BeanMix.Single` across all
  consumers.
- `visualizer/shot-sync-signatures.ts` + the `WireShot` mirror: use `field: T |
  null` where typeshare emits optional `field?: T`. `| null` vs `?:`
  (null-vs-undefined) differ at runtime, so consumers' null-handling must be
  audited, not just retyped.
- `history/model.ts` `ShotBean`: same string-union basis as `bean/model.ts`.

These don't reintroduce the staleness risk (the canonical generated types now
exist + are CI-guarded). Truly unifying them is a separate "adopt typeshare enums
on the web" refactor — left as follow-up cleanup rather than forced here.

# 05 — Generate web `crema-core.ts` from `core/bindings/` + CI freshness check

- **Status:** ready-for-agent
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

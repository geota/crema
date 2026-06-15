# 40 — Share `bestEffortRemoteDelete(ids)`

- **Status:** ✅ done
- **Severity:** P2
- **Area:** Web — `web/src/lib/components/beans/BeanDeleteSplit.svelte`, `web/src/lib/components/beans/RoasterDeleteSplit.svelte`
- **Punchlist:** T4-16 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
Free-tier-skip + `appCtx().services` guard + `console.warn` shape duplicated `BeanDeleteSplit.svelte:63-72` / `RoasterDeleteSplit.svelte:72-84`.

## Fix
Extract a shared `bestEffortRemoteDelete(ids)` function (e.g. in `web/src/lib/visualizer/` or `web/src/lib/services/`) that encapsulates the free-tier-skip check, `appCtx().services` guard, and `console.warn` fallback. Both `BeanDeleteSplit.svelte` and `RoasterDeleteSplit.svelte` import and call this function.

## Acceptance / Verify
- `grep -rn "bestEffortRemoteDelete\|free.tier\|console\.warn" web/src/lib/components/beans` shows only the import/call, not the logic
- The shared function is defined in exactly one place
- Bean and roaster remote deletes still skip correctly on free tier and warn correctly when the service is unavailable

## Touched files
- `web/src/lib/components/beans/BeanDeleteSplit.svelte:63-72` — replace with `bestEffortRemoteDelete(ids)` call
- `web/src/lib/components/beans/RoasterDeleteSplit.svelte:72-84` — replace with `bestEffortRemoteDelete(ids)` call
- new file (e.g. `web/src/lib/visualizer/bestEffortRemoteDelete.ts`) — the extracted shared function

## Comments
<!-- triage + progress notes append below -->

### 2026-06-15 (session 5) — done

Extracted `web/src/lib/visualizer/bestEffortRemoteDelete.ts` and exported it from
the `$lib/visualizer` barrel. Unified signature (covers all 3 call patterns):
`bestEffortRemoteDelete(services: CremaServices | null, beanIds: string[], roasterId?: string | null)`
— free-tier skip + null-services guard + bags-before-roaster order + best-effort
`console.warn`, all in one place.

- `services` is passed in (not read via `appCtx`) because `getCremaAppContext` is
  `getContext`-based and can't run in a plain module — call sites pass `appCtx().services`.
- No import cycle: `$lib/bean` doesn't import `$lib/visualizer`, and the
  `CremaServices` import is type-only (erased).
- Call sites: Bean `(_, [visualizerId])`; Roaster detach `(_, [], roasterVizId)`;
  cascade `(_, beanVizIds, roasterVizId)` — behaviour byte-identical to the old
  `deleteRemoteBean` / `fireRemoteDeletes`.

Verified: acceptance grep shows only import + call (no logic) in `components/beans`;
`pnpm run check` 0 errors/0 warnings; `pnpm exec vitest run` 261/261. Commit on
`effect/phase-0-spike`.

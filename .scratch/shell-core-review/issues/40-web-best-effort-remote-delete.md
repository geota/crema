# 40 — Share `bestEffortRemoteDelete(ids)`

- **Status:** ready-for-agent
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

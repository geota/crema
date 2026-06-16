# 39 — Extract `useVisualizerConnection()` rune helper

- **Status:** ✅ done
- **Severity:** P2
- **Area:** Web — `web/src/lib/components/beans/BeanDeleteSplit.svelte`, `web/src/lib/components/beans/RoasterDeleteSplit.svelte`, `web/src/lib/components/settings/sections/SharingSection.svelte`
- **Punchlist:** T4-15 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
The connection gate (`$state(false)` + `onMount` seed + `tokens.onConnectionChange`) is copy-pasted 3×: `components/beans/BeanDeleteSplit.svelte:42-51`, `RoasterDeleteSplit.svelte:43-52`, `settings/sections/SharingSection.svelte:61-66`.

## Fix
Extract a `useVisualizerConnection()` rune helper (e.g. in `web/src/lib/visualizer/useVisualizerConnection.svelte.ts`) that encapsulates the `$state(false)` + `onMount` seed + `tokens.onConnectionChange` subscription. All three call sites import and use the helper.

## Acceptance / Verify
- The connection gate block at `BeanDeleteSplit.svelte:42-51`, `RoasterDeleteSplit.svelte:43-52`, `SharingSection.svelte:61-66` is replaced by a `useVisualizerConnection()` call
- `grep -rn "onConnectionChange" web/src/lib/components` returns 0 inline subscriptions (all in the helper)
- Connection state still updates correctly in all three components when the visualizer connects/disconnects

## Touched files
- `web/src/lib/components/beans/BeanDeleteSplit.svelte:42-51` — replace with `useVisualizerConnection()`
- `web/src/lib/components/beans/RoasterDeleteSplit.svelte:43-52` — replace with `useVisualizerConnection()`
- `web/src/lib/components/settings/sections/SharingSection.svelte:61-66` — replace with `useVisualizerConnection()`
- new file `web/src/lib/visualizer/useVisualizerConnection.svelte.ts` — the extracted rune helper

## Comments
<!-- triage + progress notes append below -->

### 2026-06-15 (session 5) — done

Extracted `web/src/lib/visualizer/useVisualizerConnection.svelte.ts` — owns the
`$state` + onMount `isConnected()` seed + `onConnectionChange` subscription, returns
`{ get/set connected }`.

**Settable on purpose.** It's a getter **and** setter: SharingSection's explicit
Disconnect calls `clearTokens()` and wants the card to flip synchronously. (The
subscription *does* echo the sign-out — see below — but a tick later via the
forked runtime fiber; the setter keeps it snappy and the echo is then a no-op.)
The two delete-splits only read it.

**4 sites, not 3.** The issue/punchlist said "copy-pasted 3×". The acceptance grep
(`onConnectionChange` over `web/src/lib/components`) surfaced a **4th**:
`settings/sections/BeanSyncSection.svelte` carried the same block (with a redundant
`refreshConnected` seed). All four now call the helper:
`beans/BeanDeleteSplit`, `beans/RoasterDeleteSplit`, `settings/sections/SharingSection`,
`settings/sections/BeanSyncSection`.

**SharingSection nuances handled:**
- Its bespoke `onMount` seed-then-load (`await refreshConnected(); if (connected) loadAccount()`)
  became a guarded `$effect(() => { if (viz.connected && account===null && accountError===null) loadAccount() })`
  — loads once per connect, never loops a failure. The leftover `onMount` keeps only the
  `onSyncConfigChange` subscription.
- Corrected a **stale comment**: disconnect said "we own this transition; no listener".
  Not true — `TokenVault.clearTokens` does `Ref.set(changes, null)` on the same
  `SubscriptionRef` that `onConnectionChange` consumes, so the sign-out *does* propagate.
  Kept the local set (now via `viz.connected = false`) only as an optimistic snappiness
  win, and said so.

Verified: `grep onConnectionChange web/src/lib/components` → 0; helper + new-pattern probe
pass `svelte-autofixer` (only the known "$effect assigns state" heuristic, which is a
real external subscription / async loader, N/A); `pnpm run check` 0 errors/0 warnings;
`pnpm exec vitest run` 261/261.

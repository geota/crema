# 39 — Extract `useVisualizerConnection()` rune helper

- **Status:** ready-for-agent
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

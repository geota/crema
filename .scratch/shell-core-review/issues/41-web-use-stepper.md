# 41 — Factor a shared `useStepper` numeric core

- **Status:** ready-for-agent
- **Severity:** P3
- **Area:** Web — `web/src/lib/components/brew/QuickStepper.svelte`, `web/src/lib/components/settings/StStepper.svelte`
- **Punchlist:** T4-17 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
`components/brew/QuickStepper.svelte` and `settings/StStepper.svelte` are two implementations sharing the clamp + canonical↔display + commit logic under different chrome. Keep the two presentational shells; share the numeric core.

## Fix
Extract a `useStepper` rune/helper (e.g. `web/src/lib/components/useStepper.svelte.ts`) that owns the clamp, canonical↔display conversion, and commit logic. Both `QuickStepper.svelte` and `StStepper.svelte` import `useStepper` for their numeric behavior and keep only their presentational chrome.

## Acceptance / Verify
- Clamp + canonical↔display + commit logic is defined once in the shared helper
- `QuickStepper.svelte` and `StStepper.svelte` retain their distinct visual presentation but delegate numeric behavior to `useStepper`
- Both steppers still clamp, convert, and commit values correctly

## Touched files
- `web/src/lib/components/brew/QuickStepper.svelte` — extract numeric core, call `useStepper`
- `web/src/lib/components/settings/StStepper.svelte` — extract numeric core, call `useStepper`
- new file `web/src/lib/components/useStepper.svelte.ts` — shared clamp + canonical↔display + commit logic

## Comments
<!-- triage + progress notes append below -->

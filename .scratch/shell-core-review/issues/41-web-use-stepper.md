# 41 — Factor a shared `useStepper` numeric core

- **Status:** ✅ done
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

### 2026-06-15 (session 5) — done

Extracted `web/src/lib/components/useStepper.svelte.ts` — the shared numeric core
(clamp + canonical↔display + grid-snapped `inc` + the click-to-type state
machine). Both `brew/QuickStepper` and `settings/StStepper` call it and keep only
their own chrome (QuickStepper's dark `.qcs*` skin + `prefix`/`overridden`/`fmt`;
StStepper's size variants + optional dot).

Behaviour is **byte-preserved** via per-component knobs on the options:
- `incPrecision` — QuickStepper **2**, StStepper **4** (float-error trim in the
  non-dimension `inc`).
- decimals fallback — QuickStepper `() => (step < 1 ? 1 : 0)`, StStepper its
  explicit `decimals` prop.
- clamp — `min`/`max` optional (StStepper) and always-present (QuickStepper 0/999)
  both fall out of the same `min?.()/max?.()` guards.
- `canEdit: () => !!onChange` keeps QuickStepper's "not editable without onChange"
  guard; StStepper omits it (always editable).
- Props passed as **getters** so the core stays reactive; `draft`/`inputEl` are
  getter/setter lvalues so `bind:value`/`bind:this` work (confirmed against the
  Svelte `bind:` docs — object-property bindings are valid).

**svelte-check caught a real bug mid-refactor:** `commit: onCommit` (direct prop
ref) captured the *initial* `onCommit` — the original called it reactively inside
`inc`/`commit`. Wrapped as `commit: (n) => onCommit(n)` (matches QuickStepper's
`(n) => onChange?.(n)`).

Verified: helper + QuickStepper pass `svelte-autofixer` (0 issues); `pnpm run
check` 0/0; `pnpm exec vitest run` 261/261. **No stepper unit tests exist and
QuickStepper has ~11 consumers, so also smoke-tested live** (vite dev → Settings ›
Brew defaults, an StStepper): `inc` 18.0→18.3 (dimension grid-snap), click-to-type
edit→Enter commit 18.3→20.5→18.0 (focus via `bind:this`, two-way `draft`, `onKey`
commit), unit/value render. QuickStepper shares the same helper + binding pattern.

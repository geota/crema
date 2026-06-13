# 04 — Web `canonicalToDisplay` — route through WASM unit helpers

- **Status:** ready-for-agent
- **Severity:** P1
- **Area:** Web
- **Punchlist:** T1-04 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem

`web/src/lib/settings/format.ts:219-229` open-codes `/28.3495`, `*1.8+32`, `*14.5038`, `/29.5735` while its siblings (`convertWeight/Temp/Pressure/Volume`, `:76-124`) and its own inverse `displayToCanonical` (`:233-244`) correctly call the WASM helpers (`grams_to_oz`, `celsius_to_fahrenheit`, `bar_to_psi`, `ml_to_fl_oz`). Fork from the single source of truth inside one file.

## Fix

Rewrite `canonicalToDisplay` to delegate to the same WASM helpers.

## Acceptance / Verify

No literal conversion constant remains in `format.ts`; round-trip `displayToCanonical(canonicalToDisplay(x)) ≈ x`.

## Touched files

- `web/src/lib/settings/format.ts:219-229` — rewrite `canonicalToDisplay` to use WASM helpers
- `web/src/lib/settings/format.ts:76-124` — reference: `convertWeight/Temp/Pressure/Volume`
- `web/src/lib/settings/format.ts:233-244` — reference: `displayToCanonical` (already correct)

## Comments
<!-- triage + progress notes append below -->

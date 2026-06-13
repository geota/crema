# 17 — Refresh stale web doc-comment headers

- **Status:** ready-for-agent
- **Severity:** P3
- **Area:** web — `components/brew/QuickSheet.svelte`, `components/brew/BrewDashboard.svelte`, `routes/scale/+page.svelte`
- **Punchlist:** T2-06 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
Headers claim controls are unwired when they now are:
`components/brew/QuickSheet.svelte:17-19`, `BrewDashboard.svelte:20,375`,
`routes/scale/+page.svelte:35-37`. No matching inline TODOs exist anymore.

## Fix
Update the headers to shipped reality.

## Acceptance / Verify
- None of the three files contain doc-comment headers claiming controls are "UI-only" or "TODO wire" for functionality that is already wired.

## Touched files
- `web/src/lib/components/brew/QuickSheet.svelte:17-19` — update stale header
- `web/src/lib/components/brew/BrewDashboard.svelte:20,375` — update stale headers
- `web/src/routes/scale/+page.svelte:35-37` — update stale header

## Comments
<!-- triage + progress notes append below -->

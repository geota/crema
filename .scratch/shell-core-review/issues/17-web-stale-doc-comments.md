# 17 — Refresh stale web doc-comment headers

- **Status:** ✅ done (2026-06-15)
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

### 2026-06-15 — done
First **verified the controls are actually wired** before rewriting (didn't want
to swap one false claim for another):
- Brew: `BrewDashboard.toggleRun` runs/stops a real shot via `app.startShot` /
  `app.stopShot` ("real, not stub" — lazy profile re-upload + group flush), and
  the inline `// TODO: wire to DE1 control` markers are gone (only the headers
  still referenced them).
- Scale: `resetPeak()` → `app.resetScalePeaks()`, `startTimer()` →
  `app.startTimer()`; auto-tare / stop-on-weight moved to Settings → Brew
  defaults.

Rewrote the four stale headers to shipped reality (QuickSheet ×1, BrewDashboard
×2 + tidied the now-orphaned "vs. UI-only" section heading, scale/+page ×1).
`grep -nE "UI-only|TODO: wire to DE1|read-only|no core backing"` over the three
files → 0 stale claims. Doc-comment-only (no code/type change).

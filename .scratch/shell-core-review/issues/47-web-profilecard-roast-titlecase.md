# 47 — Title-case the web ProfileCard roast chip

- **Status:** ✅ done (2026-06-15)
- **Severity:** P3
- **Area:** Web — `ProfileCard.svelte`
- **Punchlist:** T3-10 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
`ProfileCard.svelte:136` shows raw lowercase "light/medium/dark" while every
other surface title-cases.

## Fix
Apply title-case to the roast chip label in `ProfileCard.svelte:136` so it reads
"Light" / "Medium" / "Dark" consistent with all other surfaces.

## Acceptance / Verify
- The roast chip in `ProfileCard.svelte` displays "Light", "Medium", or "Dark" (title-cased).
- No other surface that was already title-casing is affected.

## Touched files
- `web/src/lib/components/profiles/ProfileCard.svelte:136` — apply title-case to roast chip label

## Comments
<!-- triage + progress notes append below -->

### 2026-06-15 — done
The chip's casing lives in the global `styles/profiles-page.css` `.pp-card-roast`
rule (the component's `<style>` only holds the danger-action override). It was
`text-transform: uppercase` + `letter-spacing: var(--track-allcaps)` — i.e. it
rendered "LIGHT"/"MEDIUM"/"DARK" (the issue text said "lowercase"; it was
actually all-caps — either way, not title-case). Changed to
`text-transform: capitalize` and dropped the all-caps tracking (that
letter-spacing is meant for uppercase and reads wrong on title-case). The roast
data is lowercase (`roastOptions = ['light','medium','dark']`), so `capitalize`
→ "Light"/"Medium"/"Dark". Neighbouring `.pp-card-tag` chips are untouched.
`svelte-check` clean; CSS casing is deterministic (browser spot-check not run).

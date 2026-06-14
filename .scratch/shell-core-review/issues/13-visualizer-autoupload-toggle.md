# 13 — Add `visualizerAutoUpload` UI toggle + fix stale comment

- **Status:** ✅ done (2026-06-14, commit fd64879) — toggle added to Sharing → Upload options; stale comment fixed
- **Severity:** P1
- **Area:** web — `components/settings/sections/SharingSection.svelte`, `settings/store.svelte.ts`
- **Punchlist:** T2-02 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
The pref is read at upload time (`history/shot-persistence.ts:350`) but has
no UI anywhere, and `settings/store.svelte.ts:152` comments it "not currently read
anywhere" (false). Users can't disable auto-upload.

## Fix
Add an "Auto-upload finished shots" toggle to
`components/settings/sections/SharingSection.svelte`; correct the comment.

## Acceptance / Verify
- An "Auto-upload finished shots" toggle is visible in the Sharing settings section.
- Toggling it off prevents shots from being auto-uploaded on completion.
- The comment at `settings/store.svelte.ts:152` accurately reflects that the pref is read at `history/shot-persistence.ts:350`.

## Touched files
- `web/src/lib/components/settings/sections/SharingSection.svelte` — add toggle UI
- `web/src/lib/settings/store.svelte.ts:152` — fix stale comment

## Comments
<!-- triage + progress notes append below -->

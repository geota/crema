# 49 — Casing nit: "Frozen" vs "frozen"

- **Status:** ✅ done (2026-06-15)
- **Severity:** P3
- **Area:** Android tablet (`ui/screens/BrewScreen.kt` area), Android phone (`ui/phone/PhoneBrewScreen.kt`)
- **Punchlist:** T3-12 (casing half) — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
"Frozen" (tablet) vs "frozen" (phone, `PhoneBrewScreen.kt:203`) — inconsistent
capitalization of the same machine state label across form factors.

## Fix
Align casing to "Frozen" (title-case, matching tablet) in `PhoneBrewScreen.kt:203`.

## Acceptance / Verify
- `grep -n '"frozen"' android/.../ui/phone/PhoneBrewScreen.kt` returns 0 hits.
- Phone brew screen displays "Frozen" (title-case) consistent with tablet.

## Touched files
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneBrewScreen.kt:203` — "frozen" → "Frozen"

## Comments
<!-- triage + progress notes append below -->

### 2026-06-15 — done
`PhoneBrewScreen.beanLine` now emits `"Frozen"` (title-case) matching the tablet's
`BrewScreen` freshness chip. The lowercase `"frozen"` *verdict key*
(`freshnessVerdict` → `freshnessColor`) is unrelated and left untouched.

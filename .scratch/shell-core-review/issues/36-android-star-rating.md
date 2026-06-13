# 36 — Extract `CremaStarRating(...)`

- **Status:** ready-for-agent
- **Severity:** P2
- **Area:** Android tablet — `ui/screens/BeanEditScreen.kt`, `ui/screens/HistoryScreen.kt`, `ui/screens/BeansScreen.kt`
- **Punchlist:** T4-11 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
Near-identical 5-star rows: `BeanEditScreen.kt:529`, `HistoryScreen.kt:724`, + read-only inline in `BeansScreen.kt:434`.

## Fix
Extract a shared `CremaStarRating(value, onChange, sizeDp, readOnly)` composable into `ui/components/CremaComponents.kt`. All three call sites — `BeanEditScreen.kt:529`, `HistoryScreen.kt:724`, and `BeansScreen.kt:434` — use the shared composable. The `readOnly = true` variant omits `onChange` interaction.

## Acceptance / Verify
- `grep -rn "fun CremaStarRating" android/` returns exactly 1 result
- All three star-rating sites render identically in appearance; the read-only site does not respond to taps
- Phone screens that also show ratings use `CremaStarRating` (extend scope if applicable)

## Touched files
- `android/app/src/main/java/coffee/crema/ui/screens/BeanEditScreen.kt:529` — replace inline star row with `CremaStarRating`
- `android/app/src/main/java/coffee/crema/ui/screens/HistoryScreen.kt:724` — replace inline star row with `CremaStarRating`
- `android/app/src/main/java/coffee/crema/ui/screens/BeansScreen.kt:434` — replace inline read-only stars with `CremaStarRating(readOnly = true)`
- `android/app/src/main/java/coffee/crema/ui/components/CremaComponents.kt` — add `CremaStarRating(value, onChange, sizeDp, readOnly)`

## Comments
<!-- triage + progress notes append below -->

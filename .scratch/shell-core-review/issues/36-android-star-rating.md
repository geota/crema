# 36 — Extract `CremaStarRating(...)`

- **Status:** ✅ done (2026-06-15)
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

### 2026-06-15 — done
Added `CremaStarRating(value, onChange?, starDp, touchDp, spacingDp, filledTint,
emptyTint, max)` to `CremaComponents.kt` (`grep "fun CremaStarRating"` → 1).
`onChange == null` = read-only plain stars; otherwise each star gets a circular
`touchDp` tap area with tap-to-clear (the editors' shared behaviour). `starDp`/
`emptyTint` keep each site's existing density (compact inline rows vs full editor).

Replaced **8** sites + removed the two private `StarRating` defs:
- Tablet: BeanEditScreen (editor), HistoryScreen (editor + read-only list row),
  BeansScreen (read-only card).
- Phone: PhoneHistoryScreen (read-only row + editor detail), PhoneBeansScreen
  (card), PhoneBeanEditScreen (editor), PhoneBrewScreen (last-shot peek).

Single pin/favourite star icons (not 5-star ratings) were left as-is. Verified on
the tablet emulator: list-row + detail ratings render correctly. Removed the
now-unused `IconButton` / `CircleShape` imports the inline rows had left behind.

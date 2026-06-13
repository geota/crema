# 37 — Extract `CremaEmptyState(...)` (+ Scale empty-state type-scale nit)

- **Status:** ready-for-agent
- **Severity:** P3
- **Area:** Android phone + tablet — `ui/screens/BeansScreen.kt`, `ui/screens/HistoryScreen.kt`, `ui/screens/ScaleScreen.kt`, `ui/phone/PhoneHistoryScreen.kt`, `ui/phone/PhoneScaleScreen.kt`, `ui/components/CremaComponents.kt`
- **Punchlist:** T4-13 + T3-12 (empty-state half) — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
Centered empty-state hand-rolled 4× (`BeansScreen.kt:280,314`, `HistoryScreen.kt:228`, `ScaleScreen.kt:312`) and copy-pasted phone↔tablet for history.

Additionally (from T3-12): Scale empty-state uses `titleLarge` on tablet (`ScaleScreen.kt:281`) vs `titleSmall` on phone (`PhoneScaleScreen.kt:326`) — a type-scale mismatch that the shared composable should fix by standardizing on one style.

## Fix
Extract a shared `CremaEmptyState(message, action?)` composable into `ui/components/CremaComponents.kt`. All four hand-rolled sites plus the phone history copy use it. Choose a single type style for the message text (resolve the `titleLarge` vs `titleSmall` discrepancy at `ScaleScreen.kt:281` / `PhoneScaleScreen.kt:326`).

## Acceptance / Verify
- `grep -rn "fun CremaEmptyState" android/` returns exactly 1 result
- All empty-state sites on phone and tablet render with the same type style
- `ScaleScreen.kt:281` and `PhoneScaleScreen.kt:326` no longer diverge on text style — both delegate to `CremaEmptyState`

## Touched files
- `android/app/src/main/java/coffee/crema/ui/screens/BeansScreen.kt:280,314` — replace hand-rolled empty states
- `android/app/src/main/java/coffee/crema/ui/screens/HistoryScreen.kt:228` — replace hand-rolled empty state
- `android/app/src/main/java/coffee/crema/ui/screens/ScaleScreen.kt:281,312` — replace; fix `titleLarge` → shared style
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneHistoryScreen.kt` — replace phone copy
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneScaleScreen.kt:326` — replace; fix `titleSmall` → shared style
- `android/app/src/main/java/coffee/crema/ui/components/CremaComponents.kt` — add `CremaEmptyState(message, action?)`

## Comments
<!-- triage + progress notes append below -->

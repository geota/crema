# 37 — Extract `CremaEmptyState(...)` (+ Scale empty-state type-scale nit)

- **Status:** ✅ done
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

### 2026-06-15 — done (one commit; live-validated both shells)
Added `CremaEmptyState(message, modifier, icon?, description?, action?)` to
`CremaComponents.kt` — optional icon disc, the message line, an optional description
sentence, an optional action slot. Routed **9** sites: Beans ×2 + History (tablet),
Beans ×2 + History ×2 + Scale (phone), Scale (tablet). **Nit resolved:** the message
now renders `titleSmall` + Medium on BOTH shells (was `titleLarge` on tablet Scale vs
`titleSmall` on phone); description unified to `bodyMedium`. Validated live: tablet &
phone Scale "No settings yet" empty states now identical (gear disc + title + desc).
Note: the simple message sites (Beans/History "No X") go from `bodyMedium` →
`titleSmall`+Medium — same ~14sp size, slightly bolder; acceptable for the single
shared type scale the issue asked for.

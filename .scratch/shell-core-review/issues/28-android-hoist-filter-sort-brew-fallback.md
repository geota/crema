# 28 — Hoist per-domain filter/sort + brew-fallback helpers

- **Status:** ready-for-agent
- **Severity:** P2
- **Area:** Android phone + tablet — `ui/phone/PhoneBeansScreen.kt`, `ui/phone/PhoneHistoryScreen.kt`, `ui/phone/PhoneProfilesScreen.kt`, `ui/phone/PhoneBrewScreen.kt`, `ui/phone/PhoneBrewSheets.kt`, `ui/phone/PhoneScaleScreen.kt`, `ui/screens/BeansScreen.kt`, `ui/screens/HistoryScreen.kt`, `ui/screens/ProfilesScreen.kt`, `ui/screens/QuickControlsSheet.kt`
- **Punchlist:** T4-03 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
Duplicated predicates: beans (`PhoneBeansScreen.kt:75-99` ≡ `BeansScreen.kt:126-152`), history (`PhoneHistoryScreen.kt:99-122` ≡ `HistoryScreen.kt:146-168`), profiles (`PhoneProfilesScreen.kt:62-83` ≡ `ProfilesScreen.kt:109-134`); and the brew-param fallback `?: 18.0/36/93` in 5 places (`PhoneBrewScreen.kt:439-440,657-658`, `PhoneBrewSheets.kt:52-54`, `QuickControlsSheet.kt:107-109`, `PhoneScaleScreen.kt:224-229`).

Note: also resolves issue 16's "All"-filter drift and issue 01's ratio drift at the source.

## Fix
- `filterAndSortBeans()` — shared pure function covering the bean predicate + sort
- `beanFilterCounts()` — shared pure function for filter badge counts
- Extend the shared `historySortKeys` pattern to cover the history predicate
- `MainUiState.effectiveBrew(active)` — a single extension/helper on the UI state that supplies the `?: 18.0/36/93` fallbacks, eliminating the 5-site duplication

## Acceptance / Verify
- `grep -rn "?: 18.0\|?: 36\|?: 93" android/` returns 0 results (all replaced by `effectiveBrew`)
- Bean "All" filter on phone no longer shows archived items (aligns to tablet's `b.archivedAt == null`)
- Phone and tablet beans/history/profiles screens produce identical filter results for the same data

## Touched files
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneBeansScreen.kt:75-99` — replace with shared helper
- `android/app/src/main/java/coffee/crema/ui/screens/BeansScreen.kt:126-152` — replace with shared helper
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneHistoryScreen.kt:99-122` — replace with shared helper
- `android/app/src/main/java/coffee/crema/ui/screens/HistoryScreen.kt:146-168` — replace with shared helper
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneProfilesScreen.kt:62-83` — replace with shared helper
- `android/app/src/main/java/coffee/crema/ui/screens/ProfilesScreen.kt:109-134` — replace with shared helper
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneBrewScreen.kt:439-440,657-658` — replace with `effectiveBrew`
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneBrewSheets.kt:52-54` — replace with `effectiveBrew`
- `android/app/src/main/java/coffee/crema/ui/screens/QuickControlsSheet.kt:107-109` — replace with `effectiveBrew`
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneScaleScreen.kt:224-229` — replace with `effectiveBrew`
- new file (e.g. `ui/domain/FilterSortHelpers.kt`) — shared filter/sort + `effectiveBrew`

## Comments
<!-- triage + progress notes append below -->

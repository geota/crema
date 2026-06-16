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

### 2026-06-15 — triage: scoped + sequenced (pure-dedup now; do after 35)
Mapped both halves. Key finding: **the correctness fixes this issue claimed to
resolve are already landed**, so it's now pure dedup (no urgency to bundle):
- Issue 16 "All"-filter drift: phone `PhoneBeansScreen` already has
  `else -> b.archivedAt == null` ("All excludes archived (matches tablet)") —
  the predicate already matches the tablet.
- Issue 01 ratio drift: already routed through the core ratio formatter.

**Four independent sub-tasks (recommend splitting into separate commits, each
tagged issue 28):**
1. **`effectiveBrew`** — the 3-way `brewParams ?: activeProfile ?: 18/36/93`
   triple. TRUE sites (verified): `QuickControlsSheet`, `PhoneBrewSheets`,
   `PhoneScaleScreen` (dose/temp), `PhoneBrewScreen` (3 spots). Add
   `MainUiState.effectiveBrew()` (computes the active profile internally → an
   `EffectiveBrew(dose,yieldOut,brewTemp)`). **Scope caveat:** the acceptance
   grep `?: 93` also matches the per-segment/chart `seg.temp ?: 93f` defaults
   (`ProfileCurveChart`, `ProfileEditScreen`, `CanvasProfilePreview`,
   `PhoneProfileEditScreen`) and the 2-way profile-only `active?.dose ?: 18f`
   (`BrewScreen`, `ScaleScreen`) — those are a *different* concept (segment temp
   default / profile-only recipe) and should NOT be folded into `effectiveBrew`.
2. **beans** — `filterAndSortBeans()` + `beanFilterCounts()`. Predicate is
   already aligned phone↔tablet; the remaining drift is the **filter-chip
   counts** (phone "All" badge = `ui.beans.size` incl. archived vs the tablet's
   non-archived counts) — `beanFilterCounts()` should settle that.
3. **history** — extend the shared `historySortKeys` to also own the row
   predicate.
4. **profiles** — shared filter+sort; mind the per-shell nuances (hidden-facet
   fallback, `sortDesc` direction toggle).

**Sequencing:** lower leverage than 35 (which unblocks 44-B). Do 35 first, then
this. No code landed yet — left `ready-for-agent`.

### 2026-06-15 — sub-task 1 of 4 done: `effectiveBrew` (commit tagged 28)
Added `EffectiveBrew(dose,yieldOut,brewTemp)` + free `effectiveBrew(brewParams,
active)` + `MainUiState.effectiveBrew()` in `MainViewModel.kt` (by `BrewParams`).
Folded the 3-way override→profile→18/36/93 triple at all true sites: QuickControls
(free fn — no `ui`), PhoneBrewSheets, PhoneBrewScreen ×3 (447/674/716), PhoneScale
×2 (`ui.effectiveBrew()` / free fn). **Per-site caveat confirmed live:** PhoneScale's
"Add 0.5 g" `yieldOut` keeps its own `?: (dose*2)` fallback (NOT 36 g) — only its
dose+temp fold. Build green; phone Brew readouts unchanged (0.63→1.27 oz, 1:2.0,
194 °F). The acceptance grep is deliberately *not* 0 — the segment-temp
(`seg.temp ?: 93f`) and 2-way profile-only (`active?.dose ?: 18f`) matches are
different concepts (see triage caveat) and stay. **Remaining: sub-tasks 2 (beans),
3 (history), 4 (profiles).**

(Aside, out of scope: `ProfilesScreen`/Beans/History each carry their own SAF
`launchSave` launcher too — a sibling of the issue-27 Settings dedup. Worth a
follow-up "share SAF export launcher" issue.)

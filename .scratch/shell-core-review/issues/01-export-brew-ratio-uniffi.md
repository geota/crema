# 01 — Export `brew_ratio` via UniFFI + one 1-decimal ratio formatter everywhere

- **Status:** ready-for-agent
- **Severity:** P1
- **Area:** Core (UniFFI · WASM) · Android (tablet + phone)
- **Punchlist:** T1-01, T3-01 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem

Web routes through `brew_ratio` (`web/src/lib/utils/ratio.ts:22`); Android never calls core and hand-rolls `yield/dose` in 14 places with inconsistent precision (`%.1f` in 9 sites, `%.2f` in 8). Same shot reads `1:2.40` (tablet brew) vs `1:2.4` (phone). Core `brew_ratio` is WASM-only (`core/de1-wasm/src/lib.rs:257`).

Android sites: `ui/screens/BrewScreen.kt:923,937,1170`, `HistoryScreen.kt:763`, `ProfilesScreen.kt:411`, `ProfileEditScreen.kt:595`, `QuickControlsSheet.kt:232`, `ui/phone/PhoneBrewScreen.kt:475,669,766`, `PhoneHistoryScreen.kt:311,706`, `PhoneProfilesScreen.kt:279`; existing `profiles/CremaProfile.kt:56 (val ratio)`.

Additionally (T3-01): web canonical `formatRatio` = 1 decimal, but Brew dashboard bypasses it with `.toFixed(2)` (`BrewDashboard.svelte:464,1251`, `YieldRatioStepper.svelte:32`); Android tablet `%.2f`, phone `%.1f`, tablet profile card `%.1f`.

## Fix

- Add `#[uniffi::export] fn brew_ratio(dose, yield_out) -> Option<f32>` to `de1-ffi`.
- Add one Kotlin `formatRatio(dose, yield): String` (1 decimal, em-dash on null) in a shared util.
- Route all 14 Android sites + `CremaProfile.ratio` through it.
- Route the web Brew dashboard through `formatRatio` (T3-01 fix): replace `.toFixed(2)` in `BrewDashboard.svelte:464,1251` and `YieldRatioStepper.svelte:32`.

## Acceptance / Verify

`grep -rn "/ *dose\|yield.*/.*dose" android/.../ui` returns 0 ratio math; every screen shows `1:2.4` (1 decimal).

## Touched files

- `core/de1-ffi/src/lib.rs` — add `#[uniffi::export] fn brew_ratio`
- `core/de1-wasm/src/lib.rs:257` — reference implementation
- `android/app/src/main/java/coffee/crema/ui/screens/BrewScreen.kt:923,937,1170`
- `android/app/src/main/java/coffee/crema/ui/screens/HistoryScreen.kt:763`
- `android/app/src/main/java/coffee/crema/ui/screens/ProfilesScreen.kt:411`
- `android/app/src/main/java/coffee/crema/ui/screens/ProfileEditScreen.kt:595`
- `android/app/src/main/java/coffee/crema/ui/screens/QuickControlsSheet.kt:232`
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneBrewScreen.kt:475,669,766`
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneHistoryScreen.kt:311,706`
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneProfilesScreen.kt:279`
- `android/app/src/main/java/coffee/crema/profiles/CremaProfile.kt:56`
- `web/src/lib/utils/ratio.ts:22` — canonical formatter (already correct)
- `web/src/lib/components/brew/BrewDashboard.svelte:464,1251`
- `web/src/lib/components/brew/YieldRatioStepper.svelte:32`

## Comments
<!-- triage + progress notes append below -->

# 27 — Extract `SettingsConfirmDialogs(...)` (copied verbatim phone↔tablet)

- **Status:** ready-for-agent
- **Severity:** P2
- **Area:** Android phone + tablet — `ui/phone/PhoneSettingsScreen.kt`, `ui/screens/SettingsScreen.kt`
- **Punchlist:** T4-02 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
The staged-confirm block (7 `CremaConfirmDialog` bodies + SAF export launcher + all `pending*`/`confirm*` state) is copied verbatim: `PhoneSettingsScreen.kt:89-179` ≡ `SettingsScreen.kt:106-225`.

## Fix
Extract a shared `SettingsConfirmDialogs(...)` composable (or a state holder + composable pair) in the `ui/components` or `ui/screens` package. Both `PhoneSettingsScreen` and `SettingsScreen` call the extracted composable, passing necessary callbacks/state.

## Acceptance / Verify
- The verbatim block at `PhoneSettingsScreen.kt:89-179` and `SettingsScreen.kt:106-225` is replaced by a single call to the new composable
- A diff of the two callers shows no remaining dialog logic inline
- Both phone and tablet settings screens still show all confirm dialogs correctly

## Touched files
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneSettingsScreen.kt:89-179` — replace with shared composable call
- `android/app/src/main/java/coffee/crema/ui/screens/SettingsScreen.kt:106-225` — replace with shared composable call
- new file (e.g. `ui/components/SettingsConfirmDialogs.kt`) — extracted composable

## Comments
<!-- triage + progress notes append below -->

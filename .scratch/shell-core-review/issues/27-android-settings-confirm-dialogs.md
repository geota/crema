# 27 — Extract `SettingsConfirmDialogs(...)` (copied verbatim phone↔tablet)

- **Status:** ✅ done (2026-06-15)
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

### 2026-06-15 — done
New `ui/screens/SettingsConfirmDialogs.kt` (a state-holder + composable pair, as
the issue suggested):
- `SettingsConfirmState` — the 7 `confirm*`/`pending*` flags, each still backed
  by its own `rememberSaveable` (delegated through the holder, so rotation /
  process-death survival is unchanged), plus the SAF `launchSave`.
- `rememberSettingsConfirmState(vm)` — builds it (saveable flags + the
  CreateDocument export launcher, `remember`-only as before for the 1 MB Binder
  cap).
- `SettingsConfirmDialogs(state, vm, ui)` — the 7 `CremaConfirmDialog` bodies
  (reset-prefs, erase, heater voltage, AC freq, flow calibration, maintenance
  cycle, Visualizer re-pull), verbatim copy preserved.

Both shells now hold `val confirm = rememberSettingsConfirmState(vm)` + one
`SettingsConfirmDialogs(confirm, vm, ui)` call; their rows flip `confirm.*`
(~11 tablet + 7 phone call sites) and call `confirm.launchSave`. The ~90-line
verbatim block is gone from each. Dropped the now-orphaned
`rememberLauncherForActivityResult`/`ActivityResultContracts` imports both
screens left behind.

`:app:compileDebugKotlin` + `:app:testDebugUnitTest` green. Verified on the
tablet emulator: Advanced → Reset preferences shows the dialog with the correct
copy and Cancel dismisses it (phone shares the identical composable).

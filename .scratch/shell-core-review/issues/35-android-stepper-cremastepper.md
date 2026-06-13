# 35 — Route all Android steppers through `CremaStepper`

- **Status:** ready-for-agent
- **Severity:** P2
- **Area:** Android phone + tablet — `ui/screens/ProfileEditScreen.kt`, `ui/screens/SettingsScreen.kt`, `ui/screens/QuickControlsSheet.kt`, `ui/phone/PhoneSettingsScreen.kt`, `ui/phone/PhoneProfileEditScreen.kt`, `ui/components/CremaComponents.kt`
- **Punchlist:** T4-10 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
≥3 reimplementations — `EditStepper`/`StepperBox` (`ProfileEditScreen.kt:602,790`), `SetStepper`/`StepBtn` (`SettingsScreen.kt:1011,1022`), `QcStepper`/`QcStepBtn` (`QuickControlsSheet.kt:367,422`) — despite shared `CremaStepper` (`ui/components/CremaComponents.kt:1225`). Plus phone `PStepper`/`EdStepper`.

## Fix
Add a compact variant to `CremaStepper` (parameterised by size/padding), then route all callers — `EditStepper`/`StepperBox`, `SetStepper`/`StepBtn`, `QcStepper`/`QcStepBtn`, `PStepper`/`EdStepper` — through it. Consider press-and-hold repeat (no shell has it today — see T3, cross-shell UX).

## Acceptance / Verify
- `grep -rn "fun EditStepper\|fun StepperBox\|fun SetStepper\|fun StepBtn\|fun QcStepper\|fun QcStepBtn\|fun PStepper\|fun EdStepper" android/` returns 0 (all replaced)
- `CremaStepper` has a compact variant
- All stepper UIs on phone and tablet render correctly and behave identically in terms of increment/decrement

## Touched files
- `android/app/src/main/java/coffee/crema/ui/components/CremaComponents.kt:1225` — add compact variant to `CremaStepper`
- `android/app/src/main/java/coffee/crema/ui/screens/ProfileEditScreen.kt:602,790` — remove `EditStepper`/`StepperBox`, use `CremaStepper`
- `android/app/src/main/java/coffee/crema/ui/screens/SettingsScreen.kt:1011,1022` — remove `SetStepper`/`StepBtn`, use `CremaStepper`
- `android/app/src/main/java/coffee/crema/ui/screens/QuickControlsSheet.kt:367,422` — remove `QcStepper`/`QcStepBtn`, use `CremaStepper`
- phone stepper files — remove `PStepper`/`EdStepper`, use `CremaStepper`

## Comments
<!-- triage + progress notes append below -->

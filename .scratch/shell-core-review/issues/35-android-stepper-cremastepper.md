# 35 — Route all Android steppers through `CremaStepper`

- **Status:** ✅ done
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

### 2026-06-15 — triage: scoped (focused session; entangled with 26 + 50)
Read all 8 reimpls. They split into **two API shapes** that both need absorbing
into `CremaStepper(label,value,unit,onChange,step,min,max,fmt)`:
- **numeric** (`value/step/min/max/onChange`): `EditStepper`
  (ProfileEditScreen), `QcStepper` (QuickControlsSheet), `EdStepper`
  (PhoneProfileEditScreen) — closest to `CremaStepper`.
- **value-string + onMinus/onPlus**: `StepperBox` (ProfileEditScreen),
  `SetStepper` (SettingsScreen), `PStepper` (PhoneSettingsScreen) — the caller
  pre-formats the string and computes ±deltas inline, so each call site must be
  converted to the numeric API.

`CremaStepper` is the 56dp telemetry stepper (plain Row, `FilledTonalIconButton`,
`readoutSm`). The reimpls are deliberately **compact + boxed** (28–32dp
Surface-circle buttons, `surfaceContainerHigh/Highest` rounded box,
`JetBrainsMono` 15–18sp value). Unifying needs a `compact`/`boxed` variant on
`CremaStepper` (button size, container, value style) — ~5 new params — plus
per-call-site conversion (~15 sites across the profile editor, QC sheet, and
both settings shells).

**Entanglements (do these together, one focused session):**
- **Issue 26** also rehomes `SetStepper`/`PStepper` (they're part of the
  settings-row set). Coordinate so the steppers aren't unified twice.
- **Issue 50**: `EditStepper` carries a `compareSymbol`/`onCompare` inline
  `>`/`<` control — that's exactly issue 50's "inline the `>`/`<`". The compact
  `CremaStepper` variant should grow an optional `compareSymbol` so 50 lands here
  too.

Not started — visual-fidelity-sensitive (dense editors) and best done as its own
pass. Left `ready-for-agent`. Prereq for the deferred 44-Category-B
(unit-aware steppers).

### 2026-06-15 — done (one commit; live-validated on both emulators)
Unified into a single `CremaStepper` with a `CremaStepperStyle` recipe (the numeric
core — step/min/max, 2-decimal snap, optional chips/header/compareSymbol — is shared;
only the *look* varies). Five named presets cover every surface, so call sites pick a
style instead of re-deriving pixels:
- **Telemetry** (default, unchanged) — brew/scale tonal-button readouts.
- **Boxed** — prominent filled field (ProfileEdit Target tiles + Quick Controls bar).
- **BoxedDense** — dense filled field (ProfileEdit segment rows; carries `compareSymbol`).
- **Bare** — 36dp bare row (tablet settings).
- **BareCompact** — 34dp bare row (phone settings + phone editors).

Deleted **8** reimpls + their button/chip helpers: `EditStepper`/`StepperBox`/
`EditStepBtn` (ProfileEdit), `QcStepper`/`QcChip`/`QcStepBtn` (QuickControls),
`SetStepper`/`StepBtn` (Settings), `PStepper`/`PStepBtn` (PhoneSettings), `EdStepper`
(PhoneProfileEdit). Routed **55** call sites; string-API sites (Set/P/StepperBox)
converted to the numeric API (bounds matched to the old inline coercions / VM clamps).

**Issue 50 folded in here partially:** the inline `>`/`<` compare control is now a
first-class `compareSymbol`/`onCompare` on `CremaStepper` (BoxedDense). The *phone*
segment-option selectors (50's other half) are still open — see issue 50.

**Issue 26 coordination:** the steppers are now fully unified; 26 only needs to
collapse the settings-*row* chrome (label/subtitle/divider/`notImplemented`), not the
steppers. No double-unification.

Validated live: tablet (Bare settings, Boxed Quick Controls w/ chips+header+live-ratio,
BoxedDense segments w/ working `>`→`<` flip, Telemetry bean editor) + phone (BareCompact
settings). Increment/decrement, the `1:%.1f` ratio format, and enabled-greying (Yield
when stop-on-weight off; gated segment Volume/Exit/Max) all confirmed.

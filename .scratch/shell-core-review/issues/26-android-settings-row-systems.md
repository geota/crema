# 26 — Collapse the three Android settings-row systems

- **Status:** ready-for-agent
- **Severity:** P2
- **Area:** Android phone + tablet — `ui/phone/components/CremaPhoneComponents.kt`, `ui/phone/PhoneSettingsScreen.kt`, `ui/screens/SettingsScreen.kt`
- **Punchlist:** T4-01 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
Shared `SettingsRow` (`ui/phone/components/CremaPhoneComponents.kt:408`) is **dead** (referenced only in a comment, `:53`); phone defines private `PRow/PPill/PSelect/PStepper/PMono/PStatusDot` (`PhoneSettingsScreen.kt:924-1019`) near-duplicating tablet's private `SetRow/SetPill/SetSelect/SetStepper/MonoReadout/StatusDot` (`SettingsScreen.kt:945-1041`). Differences are only padding + a FlowRow pill wrap.

## Fix
One parameterized row set (pill/connection semantics shared); delete the dead `SettingsRow` and both private copies.

## Acceptance / Verify
- `grep -rn "PRow\|PPill\|PSelect\|PStepper\|PMono\|PStatusDot" android/` returns 0 results
- `grep -rn "SetRow\|SetPill\|SetSelect\|SetStepper\|MonoReadout\|StatusDot" android/` returns 0 results (or only the new shared definition)
- Dead `SettingsRow` at `CremaPhoneComponents.kt:408` is removed
- Phone and tablet settings screens render identically in layout (padding delta acceptable via param)

## Touched files
- `android/app/src/main/java/coffee/crema/ui/phone/components/CremaPhoneComponents.kt:53,408` — remove dead `SettingsRow`
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneSettingsScreen.kt:924-1019` — delete private `PRow/PPill/PSelect/PStepper/PMono/PStatusDot`
- `android/app/src/main/java/coffee/crema/ui/screens/SettingsScreen.kt:945-1041` — delete private `SetRow/SetPill/SetSelect/SetStepper/MonoReadout/StatusDot`
- new shared file (e.g. `ui/components/SettingsRowComponents.kt`) — parameterized row set

## Comments
<!-- triage + progress notes append below -->

### 2026-06-15 — scope shrunk: steppers + status dot already extracted
Two of the six row widgets are already unified, so this issue no longer owns them:
- **`SetStepper`/`PStepper`** → done in **issue 35** (now `CremaStepper`/`CremaStepperStyle.Bare`/`BareCompact`).
- **`StatusDot`/`PStatusDot`** → extracted to shared **`CremaStatusDot`** (`CremaComponents.kt`),
  byte-identical to both originals; all 8 call sites routed. (Prompted during 35.)

**Remaining for 26:** the row chrome + the other shared widgets —
`PRow/PPill/PSelect/PMono` ↔ `SetRow/SetPill/SetSelect/MonoReadout`, plus the dead
`SettingsRow` at `CremaPhoneComponents.kt:408`. Update the acceptance greps: drop
`PStepper`/`SetStepper`/`PStatusDot`/`StatusDot` (already 0), keep the rest.

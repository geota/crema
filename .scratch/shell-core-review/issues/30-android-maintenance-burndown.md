# 30 — Hoist maintenance burn-down math

- **Status:** ready-for-agent
- **Severity:** P2
- **Area:** Android phone + tablet — `ui/phone/PhoneSettingsScreen.kt`, `ui/screens/SettingsScreen.kt`
- **Punchlist:** T4-05 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
Filter %, descale L/L, clean h/h + note strings duplicated `PhoneSettingsScreen.kt:533-551` vs `SettingsScreen.kt:434-477`.

## Fix
A pure helper on the maintenance readout model — e.g. an extension function or data class on `MaintenanceReadout` that computes filter %, descale progress (L used / L capacity), clean progress (h used / h capacity), and the associated note strings. Both phone and tablet settings screens call this helper instead of inlining the math.

## Acceptance / Verify
- The duplicated blocks at `PhoneSettingsScreen.kt:533-551` and `SettingsScreen.kt:434-477` are replaced by calls to the shared helper
- Phone and tablet maintenance readouts display identical values for the same `MaintenanceReadout` input

## Touched files
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneSettingsScreen.kt:533-551` — replace with shared helper call
- `android/app/src/main/java/coffee/crema/ui/screens/SettingsScreen.kt:434-477` — replace with shared helper call
- maintenance readout model file — add the pure burn-down helper

## Comments
<!-- triage + progress notes append below -->

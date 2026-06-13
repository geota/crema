# 29 — Reconcile low-tank threshold (phone 20f vs tablet 5f)

- **Status:** ready-for-agent
- **Severity:** P3
- **Area:** Android phone + tablet — `ui/phone/PhoneSettingsScreen.kt`, `ui/screens/SettingsScreen.kt`
- **Punchlist:** T4-04 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
`LOW_TANK_MM_PHONE = 20f` (`PhoneSettingsScreen.kt:487`) vs `LOW_TANK_MM = 5f` (`SettingsScreen.kt:82`) — refill warning fires at different levels.

## Fix
Determine the correct threshold (likely 5f matching the more conservative tablet value, or the value specified in hardware documentation), define it once as a shared constant (e.g. in a `CremaConstants.kt` or the domain model), and reference it from both phone and tablet settings screens.

## Acceptance / Verify
- Only one definition of the low-tank threshold constant exists in the Android codebase
- Phone and tablet show the refill warning at the same water level

## Touched files
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneSettingsScreen.kt:487` — remove `LOW_TANK_MM_PHONE`, use shared constant
- `android/app/src/main/java/coffee/crema/ui/screens/SettingsScreen.kt:82` — move `LOW_TANK_MM` to shared location

## Comments
<!-- triage + progress notes append below -->

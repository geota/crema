# 33 — Share scale metadata + capability body

- **Status:** ready-for-agent
- **Severity:** P2
- **Area:** Android phone + tablet — `ui/phone/PhoneScaleScreen.kt`, `ui/screens/ScaleScreen.kt`
- **Punchlist:** T4-08 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
`scaleMeta(ui)` verbatim copy (`PhoneScaleScreen.kt:45-50` ≡ `ScaleScreen.kt:74-79`); capability-gated settings body reimplemented (`PhoneScaleScreen.kt:172-314` vs `ScaleScreen.kt:291-382`) — phone even adds a wired display-mode row tablet drops (`:265-273`).

## Fix
Extract `scaleMeta(ui)` to a shared location (e.g. `ui/scale/ScaleHelpers.kt`) so both screens call the same function. Extract the capability-gated settings body into a shared composable `ScaleCapabilitySettings(...)` with a parameter to opt into the phone-only display-mode row, or reconcile whether tablet should also show it.

## Acceptance / Verify
- `scaleMeta` is defined once; `grep -rn "fun scaleMeta" android/` returns exactly 1 result
- The capability-gated body is implemented once; phone and tablet scale screens delegate to it
- Phone's display-mode row (`PhoneScaleScreen.kt:265-273`) is preserved or promoted to the shared body with a flag

## Touched files
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneScaleScreen.kt:45-50` — replace with shared `scaleMeta`
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneScaleScreen.kt:172-314` — replace with shared capability composable
- `android/app/src/main/java/coffee/crema/ui/screens/ScaleScreen.kt:74-79` — replace with shared `scaleMeta`
- `android/app/src/main/java/coffee/crema/ui/screens/ScaleScreen.kt:291-382` — replace with shared capability composable
- new file (e.g. `ui/scale/ScaleHelpers.kt`) — `scaleMeta` + `ScaleCapabilitySettings`

## Comments
<!-- triage + progress notes append below -->

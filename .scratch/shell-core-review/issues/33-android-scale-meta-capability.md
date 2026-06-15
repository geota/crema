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

### 2026-06-15 — triage: deferred (sequence after 26 + 35; body-merge unverifiable here)
Looked at both screens closely. Two distinct sub-tasks with very different risk:

1. **`scaleMeta(ui)`** — genuinely byte-identical (`ScaleScreen.kt:76` ≡
   `PhoneScaleScreen.kt:45`). Trivial, safe to share (make the tablet's
   top-level fn non-private, phone imports it — the established
   `coffee.crema.ui.screens.X` pattern). Fold this into the body-merge commit.

2. **Capability body** — NOT a value-identical hoist; it's a real design-merge:
   - Tablet `ScaleSettingsPanel` runs off the **mapped** `ScaleCapabilities`
     (camelCase via `mapCaps`), renders rows with `ToggleRow`/`SegRow` +
     `HorizontalDivider` inside a `CremaCard`, **omits** the display-mode row
     (`mapCaps` drops `modes`), auto-stop labels "Flow-stop"/"Cup-removal",
     footer = `CremaButton`s.
   - Phone `ConnectedBody` runs off the **core** caps directly (snake_case
     `caps.flow_smoothing`…`caps.modes`), renders with phone `SettingsRow` in a
     `Surface`, **includes** the display-mode row, auto-stop "Flow"/"Cup",
     footer = `FilledTonalButton`/`TextButton`.
   Merging forces one canonical presentation (row component, labels, footer) +
   a **product decision**: should the tablet also show the display-mode row?
   (`set_scale_mode`/`scaleActiveMode` already wired in the VM.)

**Why deferred, not done now:**
- **Unverifiable on the emulators.** The capability rows only render with a
  connected scale (`caps == null` → "No settings yet" empty state otherwise),
  and there's no scale simulator (same gap as the DE1). A design-consistency
  merge I can't see is high-risk.
- **Entangled with 26 + 35.** The canonical settings-row component (issue 26)
  and the canonical `CremaStepper` (issue 35, the auto-sleep stepper) should be
  settled first, or the scale rows get unified twice. Do 33 AFTER 26 + 35.

**Recommendation:** bundle `scaleMeta` + the body-merge into one commit once 26
& 35 land; decide the tablet-modes-row question (lean: promote to the shared
body behind a `showDisplayMode` flag, tablet currently `false` to stay
value-preserving — or grant the tablet parity if the operator wants it). Verify
behind a scale when hardware is available.

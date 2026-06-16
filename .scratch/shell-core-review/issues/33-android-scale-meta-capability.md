# 33 — Share scale metadata + capability body

- **Status:** ✅ done 2026-06-16 — `scaleMeta` shared (part 1) + capability body merged (part 2); also retires the dead `SettingsRow` (closes 26's deferral)
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

### 2026-06-15 — part 1 DONE (`scaleMeta` shared); body-merge deferred per the handoff
**`scaleMeta(ui)` shared.** Promoted the tablet's to non-private
(`ScaleScreen.kt`); deleted the phone's byte-identical copy; phone imports
`coffee.crema.ui.screens.scaleMeta`. `grep "fun scaleMeta"` → exactly 1. Build green.

**Capability-body merge deferred — it is NOT a value-preserving hoist.** Read both
closely post-26: merging forces ONE canonical presentation across choices that
genuinely differ today, and the rows only render with a **connected scale** (no
simulator), so the result is **unverifiable on the emulators**. The decision surface,
captured for a session with a scale (or an operator design call):
  | aspect | tablet `ScaleSettingsPanel` | phone `ConnectedBody` |
  |---|---|---|
  | container | `CremaCard` | `Surface(surfaceContainer)` |
  | rows | `ToggleRow`/`SegRow` (custom, dividers) | public `SettingsRow` (no dividers) |
  | caps model | mapped `ScaleCapabilities` (camelCase) + 11 params | core caps (snake_case) + `vm`/`ui` direct |
  | auto-stop labels | "Flow-stop" / "Cup-removal" | "Flow" / "Cup" |
  | display-mode row | **omitted** (`mapCaps` drops `modes`) | **included** (`set_scale_mode` wired) |
  | footer | `CremaButton` Tonal/Text-danger | `FilledTonalButton`/`TextButton` |
  | empty state | inline `CremaEmptyState` (`caps == null`) | n/a (body only when connected) |

**Suggested canonical (for when it's done):** route both onto the now-unified
`CremaSettingsRow` (issue 26; dense on phone, roomy on tablet — this also retires the
public `SettingsRow`, the piece 26 deferred here), `CremaButton` footer, one label set,
and a `showDisplayMode` flag (tablet `false` unless the operator wants parity — the VM
already wires `set_scale_mode`/`scaleActiveMode`). **Left for a scale-equipped session**
per the handoff ("verify behind a scale when hardware is available"); doing it blind
would reshape a screen no one can see.

### 2026-06-16 — Part 2 DONE (operator: don't defer — no scale in dev, ever)
Merged the body. Two shared composables in `ScaleScreen.kt` (non-private; phone imports them):
- **`ScaleCapabilityRows(vm, ui, caps, showDisplayMode = true)`** — the capability-gated
  rows, each via the unified **`CremaSettingsRow`** (issue 26), so the phone gets dense
  rows under its `LocalSettingsRowDense` and the tablet the roomy default. Reads the
  **core** caps directly (snake_case) + `vm`/`ui` — no more mapped-caps + 11-param panel.
- **`ScaleSettingsFooter(vm, canBeep, modifier)`** — Beep + Disconnect via **`CremaButton`**.

Canonical choices made (the decision-table resolved): one row component (`CremaSettingsRow`),
`CremaButton` footer, short "Flow"/"Cup" auto-stop labels, **display-mode row shown on
both** shells (was phone-only — the tablet's mapped model just never carried `modes`).
Each call site keeps its own container: phone `Surface` (+ `CompositionLocalProvider`
dense), tablet `CremaCard` + Eyebrow + the `caps == null` empty state + scroll/pinned
footer. Deleted the tablet's `ToggleRow`/`SegRow`.

**Also retired the dead `SettingsRow`** (`CremaPhoneComponents.kt`) — the phone scale screen
was its last user → **closes issue 26's deferral**. Removed a dead `FontWeight` import.

**Verification:** `:app:assembleDebug` green; both apps launch healthy on the emulators
(adb — the android MCP had wedged). The scale **capability rows themselves stay
unverifiable** (no scale reaches the emulator on macOS — see [[android-build-run-verify]]);
the tablet empty-state path is standard components (`CremaCard`/`Eyebrow`/`CremaEmptyState`).
Per the operator: deferring gained nothing (no dev scale ever), so shipped build-verified.

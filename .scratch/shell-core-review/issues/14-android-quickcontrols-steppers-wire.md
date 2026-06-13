# 14 — Wire Android QuickControls steam/water/flush steppers

- **Status:** ready-for-agent
- **Severity:** P1
- **Area:** Android tablet — `ui/screens/QuickControlsSheet.kt`
- **Punchlist:** T2-03 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
Steam (time/flow/temp), hot-water (temp/volume), flush (time/temp) steppers
write only `remember` state (`ui/screens/QuickControlsSheet.kt:118-127`, set at
`:257,270,283`); reset on close, change nothing — yet core exposes the setters on both
bindings (`set_steam_flow`, `set_steam_hotwater_settings`, `set_flush_temp/timeout/
flow_rate`, `set_hot_water_idle_temp`).

## Fix
Add `onChange` callbacks → VM methods backed by those FFI setters; or, if the
per-mode param store isn't ready, gate the steppers behind a visible "Not implemented"
affordance (see T2-04) rather than letting them silently no-op.

## Acceptance / Verify
- Adjusting a steam/water/flush stepper in QuickControls causes the machine to receive the updated value (observable via machine state readout or logs).
- If full wiring is not yet ready, each unimplemented stepper shows a visible "Not implemented" affordance instead of silently accepting input.

## Touched files
- `android/app/src/main/java/coffee/crema/ui/screens/QuickControlsSheet.kt:118-127,257,270,283` — add onChange callbacks or gate steppers

## Comments
<!-- triage + progress notes append below -->

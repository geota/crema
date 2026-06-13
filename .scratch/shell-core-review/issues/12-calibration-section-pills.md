# 12 — Remove false "Not implemented yet" pills from CalibrationSection

- **Status:** ready-for-agent
- **Severity:** P1
- **Area:** web — `components/settings/sections/CalibrationSection.svelte`
- **Punchlist:** T2-01 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
Temp/Pressure/Flow calibration rows carry `notImplemented`
(`web/src/lib/components/settings/sections/CalibrationSection.svelte:379,419,459`) whose
tooltip says *"no part of the app reads it yet"* — but the Apply/Reset handlers call
`app.writeCalibration` / `resetCalibrationToFactory` / `setCalibrationFlowMultiplier`
(`:201,203,342`), which exist (`state/app.svelte.ts:1469,1484,1509`) and reach core. The
pill lies about a hardware-write control.

## Fix
Delete `notImplemented` from those three rows; keep `needsConnection`.

## Acceptance / Verify
With a DE1 connected, the three rows are active and apply calibration.

## Touched files
- `web/src/lib/components/settings/sections/CalibrationSection.svelte:379,419,459` — remove `notImplemented` prop from three rows
- `web/src/lib/state/app.svelte.ts:1469,1484,1509` — verify handlers exist (no change needed)

## Comments
<!-- triage + progress notes append below -->

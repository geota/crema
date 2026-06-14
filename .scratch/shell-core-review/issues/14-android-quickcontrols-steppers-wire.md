# 14 — Wire Android QuickControls steam/water/flush steppers

- **Status:** ✅ done (2026-06-14) — persisted QC + RMW on both shells; Android verified end-to-end on tablet emulator (no-crash + survives restart), web type-checks; machine write DE1-gated
- **Severity:** P1
- **Area:** Android tablet — `ui/screens/QuickControlsSheet.kt`; web — `components/brew/*`

> **Decision (2026-06-14):** Implement via **read-modify-write**, and **persist** the
> QC steam/water/flush values (drop the throwaway `remember`) so they stick + take
> priority. No global Settings UI yet (the persisted QC value *is* the default for
> now); no profile layer (steam/water/flush aren't profile-scoped). Apply to both
> Android + web. Effective cascade: **persisted-QC → machine (RMW)**.
>
> - `steam_flags = 0` is canonical (legacy app always writes 0; bits undocumented).
> - RMW preserves the machine's `hot_water_timeout` / `espresso_volume` / `group_temp`
>   from `Event.ShotSettingsRead` (cache it; fall back to legacy defaults pre-read).
> - Android: persist 7 values (AppPrefs + UiState + currentPrefs/loadPrefs); cache
>   ShotSettingsRead; VM setters apply via `setSteamHotwaterSettings` (steam temp/time
>   + hot-water temp/vol), `setSteamFlow`, `setFlushTimeout`/`setFlushTemp`; QC seeds
>   from persisted + calls the setters on change.
> - Web: persist the same in the settings store + RMW through the core facade
>   (`setSteamHotwaterSettings` / `setSteamFlow` / `setFlush*`).
> - **DE1-gated:** the machine write can't be verified without hardware (emulator/CI
>   confirm build + no-crash only).
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

### 2026-06-14 — implemented (both shells)
- **Android:** persisted 7 QC values in `AppPrefs` + `MainUiState`
  (currentPrefs/loadPrefs); cache `Event.ShotSettingsRead` into
  `machineShotSettings`; 7 VM setters (`setQc*`) update UiState → persist →
  write the machine. Steam temp/time + hot-water temp/vol share the cuuid_0B
  packet via `applySteamHotWater()` → `bridge.setSteamHotwaterSettings`;
  steam-flow / flush-time / flush-temp are standalone writes. RMW field-merge
  extracted to pure `buildSteamHotWaterSettings(...)` (top-level `internal`) +
  4 JVM unit tests (`SteamHotWaterRmwTest`). `QuickControlsSheet` steam/water/
  flush steppers now take persisted values + callbacks (modes stay local).
- **Web:** added `setSteamHotwaterSettings` to the core facade (`core/index.ts`,
  8-scalar wasm bridge call) + `SteamHotwaterSettings` type; `App.setSteamHotwater`
  (RMW, seeds unmodeled fields from `de1ShotSettings`), `setSteamFlow`,
  `setFlushTimeoutS`. Persisted 7 `qc*` fields in the settings store; `BrewParamState`
  gained a `qcSeed` getter (read `untrack`ed so a QC edit doesn't re-seed dose/yield);
  `BrewDashboard` `onWrite` routes each key → persist + machine.
- **Verify:** `:app:compileDebugKotlin` + `:app:testDebugUnitTest` green; web
  `npm run check` 0 errors. Tablet emulator: adjusting Steam (→15 s) / Hot water
  (→180 ml) / Flush (→6 s) does not crash and the values survive a force-stop +
  relaunch. The actual BLE write is DE1-gated (no hardware/sim).

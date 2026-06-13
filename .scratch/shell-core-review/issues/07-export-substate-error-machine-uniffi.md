# 07 — Export `sub_state_error_message` + machine model/feature fns via UniFFI

- **Status:** done
- **Severity:** P2
- **Area:** Core (UniFFI · WASM) · Android
- **Punchlist:** T1-07 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem

Web shows human-readable machine errors via `ui-state.svelte.ts:630` → `sub_state_error_message`; Android shows the raw substate string (`MainViewModel.kt:2690`). All four fns (`sub_state_error_message`, `is_recoverable`, `machine_model_name`, `has_cup_warmer`) are WASM-only.

## Fix

- Export the four fns as one-line wrappers via UniFFI in `de1-ffi`.
- Wire Android to show readable error text using `sub_state_error_message`/`is_recoverable`.
- Use `machine_model_name`/`has_cup_warmer` in the Android About/Settings readout.

## Acceptance / Verify

Android shows the same human-readable error string as web for a known machine substate error; machine model name appears correctly in Android Settings/About without a raw enum string.

## Touched files

- `core/de1-ffi/src/lib.rs` — export `sub_state_error_message`, `is_recoverable`, `machine_model_name`, `has_cup_warmer`
- `android/app/src/main/java/coffee/crema/ui/MainViewModel.kt:2690` — replace raw substate string with readable error text
- `web/src/lib/state/ui-state.svelte.ts:630` — reference implementation

## Comments
<!-- triage + progress notes append below -->

### 2026-06-13 — done

- **Core:** exported all four via `#[uniffi::export]` in `de1-ffi`, one-line
  delegates mirroring the wasm exports: `sub_state_error_message(name) ->
  Option<String>`, `is_recoverable(tag, Option<u16>) -> bool`,
  `machine_model_name(u32) -> String`, `has_cup_warmer(u32) -> bool`. de1-ffi
  already depends on `de1-protocol`. Bindgen confirms all four.
- **Android — machine model / cup warmer:** `machineModelLabel` and
  `hasCupWarmerPlate` (in `SettingsScreen.kt`, also used by `PhoneSettingsScreen`)
  now delegate to core `machineModelName` / `hasCupWarmer`, deleting the
  hand-rolled 1..7 table + the `4..7` range literal. Web parity: the unknown/
  fallback cases now read `"unknown"` / `"model N"` (core's exact strings) vs the
  old `"Unknown"` / `"Model N"`; `—` is still shown when the register is unread.
  No logic compares against the old strings (only `.takeIf { it != "—" }`).
- **Android — substate error:** new `MainUiState.machineError`, computed in
  `MachineStateChanged` from core `subStateErrorMessage(substate)` (null for
  healthy substates), surfaced as a conditional "Machine error" diagnostics row
  (error colour) in both tablet and phone Settings. Mirrors web's `machineErrorText`.
- **`is_recoverable`:** exported but not wired — it classifies *Visualizer call*
  errors, a different surface than machine substate, and Android has no caller for
  it yet (latent, like issue 10).
- **Verify:** `cargo build -p de1-ffi` clean; bindgen lists all four. Android
  reviewed by eye (no NDK): binding param/return types line up (`UInt` model
  values pass straight to `machineModelName`/`hasCupWarmer`; `subStateErrorMessage`
  is non-throwing `String?`); `PMono`/`MonoReadout` both take a `color`.

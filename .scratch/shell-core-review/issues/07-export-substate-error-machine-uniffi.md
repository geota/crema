# 07 — Export `sub_state_error_message` + machine model/feature fns via UniFFI

- **Status:** ready-for-agent
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

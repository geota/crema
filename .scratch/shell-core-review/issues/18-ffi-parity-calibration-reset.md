# 18 — Close non-domain FFI parity gaps (calibration-write, reset-defaults)

- **Status:** ready-for-agent
- **Severity:** P2
- **Area:** core — `de1-wasm/src/lib.rs`, `de1-ffi/src/lib.rs`
- **Punchlist:** T5-01 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
`write_calibration`, `reset_calibration_to_factory`
(`de1-wasm:1200-1212`) and `reset_machine_defaults` (`de1-wasm:1646`) are WASM-only —
blockers the moment Android surfaces calibration-write / factory-reset controls.

## Fix
Mirror in `de1-ffi::CremaBridge` (thin wrappers identical to the read variants).

## Acceptance / Verify
- `de1-ffi/src/lib.rs` exports `write_calibration`, `reset_calibration_to_factory`, and `reset_machine_defaults` as `#[uniffi::export]` methods.
- The UniFFI-generated Kotlin bindings include all three methods.

## Touched files
- `core/de1-wasm/src/lib.rs:1200-1212,1646` — reference implementations
- `core/de1-ffi/src/lib.rs` — add the three thin wrapper exports

## Comments
<!-- triage + progress notes append below -->

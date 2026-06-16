# 18 — Close non-domain FFI parity gaps (calibration-write, reset-defaults)

- **Status:** ✅ done 2026-06-15
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

### 2026-06-15 — DONE
Mirrored all three into `de1-ffi::CremaBridge` as thin wrappers identical to the
read variants / `set_heater_voltage`:
- `write_calibration(sensor, reported, measured) -> String` and
  `reset_calibration_to_factory(sensor) -> String` (infallible, beside
  `read_factory_calibration`).
- `reset_machine_defaults() -> Result<String, CremaError>` (beside
  `set_heater_voltage`; core returns `Result<CoreOutput, AppError>`, so it maps
  via `crema_err` → UniFFI surfaces a throwing Kotlin method, `@Throws(CremaException::class)`).

**Verified:**
- `cargo test -p de1-ffi` → 33 pass (extended the calibration test with
  write/reset; added `reset_machine_defaults_succeeds_with_write_commands`).
- Android `:app:assembleDebug` regenerates the UniFFI Kotlin; the bindings now
  expose `writeCalibration` / `resetCalibrationToFactory` / `resetMachineDefaults`
  (generated `de1_ffi.kt` is gitignored — regenerated at build, not committed).
- clippy clean. **fmt:** my additions add zero `cargo fmt` diffs; note the repo's
  current de1-ffi already shows 5 pre-existing chain-collapse diffs under newer
  rustfmt (disable_scale_lcd/scale_heartbeat/power_off_scale/scale_beep/toggle_scale_unit)
  — left untouched (out of scope; CI keeps them multi-line).

Web parity already existed (these are the de1-wasm reference impls); no web change.

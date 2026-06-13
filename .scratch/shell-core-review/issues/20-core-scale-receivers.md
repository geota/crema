# 20 — Align scale-command receivers `&mut self` → `&self` in WASM

- **Status:** ready-for-agent
- **Severity:** P2
- **Area:** core — `de1-wasm/src/lib.rs`
- **Punchlist:** T5-03 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
`tare_scale` (`de1-wasm:1216`) and `set_scale_volume/standby/
flow_smoothing/anti_mistouch/mode/auto_stop` take `&mut self` in WASM but `&self` in FFI;
none mutate `CremaBridge` state.

## Fix
Change to `&self` to match FFI and correctness semantics.

## Acceptance / Verify
- `tare_scale` and all `set_scale_*` methods in `de1-wasm/src/lib.rs` take `&self` (not `&mut self`).
- The WASM build compiles cleanly with no mutation errors.

## Touched files
- `core/de1-wasm/src/lib.rs:1216` — `tare_scale` receiver
- `core/de1-wasm/src/lib.rs` — all `set_scale_*` method receivers

## Comments
<!-- triage + progress notes append below -->

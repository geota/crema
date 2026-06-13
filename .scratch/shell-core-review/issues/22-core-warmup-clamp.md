# 22 — Replace `u64::MAX as f32` warmup clamp ceiling

- **Status:** ready-for-agent
- **Severity:** P3
- **Area:** core — `de1-wasm/src/lib.rs`, `de1-ffi/src/lib.rs`
- **Punchlist:** T5-05 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
`de1-wasm:1194` / `de1-ffi:1194` clamp to `u64::MAX as f32` (precision loss)
under a blanket clippy allow.

## Fix
A named domain ceiling constant (e.g. 1h in ms) cast once.

## Acceptance / Verify
- The blanket clippy allow for the `u64::MAX as f32` cast is removed.
- A named constant (e.g. `MAX_WARMUP_TIMEOUT_MS`) is introduced and used as the clamp ceiling.
- The Rust code compiles cleanly without the allow attribute.

## Touched files
- `core/de1-wasm/src/lib.rs:1194` — replace `u64::MAX as f32` clamp
- `core/de1-ffi/src/lib.rs:1194` — replace `u64::MAX as f32` clamp

## Comments
<!-- triage + progress notes append below -->

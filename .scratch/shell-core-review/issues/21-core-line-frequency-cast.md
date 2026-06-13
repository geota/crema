# 21 — Fix `set_line_frequency_override` truncating cast

- **Status:** ready-for-agent
- **Severity:** P3
- **Area:** core — `de1-wasm/src/lib.rs`, `de1-ffi/src/lib.rs`
- **Punchlist:** T5-04 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
`hz as i32` (`de1-wasm:1817`, `de1-ffi:1301`) silently accepts 50.9→"50"
despite the doc saying only 0/50/60.

## Fix
`hz.round() as i32`, or match `hz == 0.0 || 50.0 || 60.0` exactly.

## Acceptance / Verify
- Passing `50.9` to `set_line_frequency_override` either rounds to `51` (rejected as invalid) or is rejected outright — it must not silently truncate to `50`.
- Only values 0, 50, and 60 are accepted as valid.

## Touched files
- `core/de1-wasm/src/lib.rs:1817` — fix truncating cast
- `core/de1-ffi/src/lib.rs:1301` — fix truncating cast

## Comments
<!-- triage + progress notes append below -->

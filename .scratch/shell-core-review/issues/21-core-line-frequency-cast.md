# 21 — Fix `set_line_frequency_override` truncating cast

- **Status:** ✅ done (2026-06-15)
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

### 2026-06-15 — done
Took the issue's first option: `match hz as i32` → `match hz.round() as i32` in
both `de1-wasm:set_line_frequency_override` and `de1-ffi:set_line_frequency_override`,
with a code comment at each site. Now `50.9` rounds to 51 → falls to the reject
arm; only values rounding to 0/50/60 pass. Kept the integer `match` (a float
`match hz { 50.0 => … }` would trip `illegal_floating_point_literal_pattern`,
which `future_incompatible = warn` + CI `-D warnings` turns into an error).

Added a de1-wasm regression test `set_line_frequency_override_rounds_rather_than_truncates`:
50/60/0 pass (and 50.0 pins `line_frequency_hz()` to `Some(50.0)`); 50.9 and 49.0
reject. The test fails against the old truncating cast (50.9 → 50 → Ok).

Verify: `cargo clippy -p de1-wasm -p de1-ffi --all-targets -- -D warnings` clean;
new test green. (The inner `de1-app::set_line_frequency_override(Option<f32>)` is
unchanged — the bridges do the 0/50/60 validation before handing it the option.)

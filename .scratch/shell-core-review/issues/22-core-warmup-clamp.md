# 22 — Replace `u64::MAX as f32` warmup clamp ceiling

- **Status:** ✅ done (2026-06-15)
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

### 2026-06-15 — done (no allow, behaviour preserved)
Replaced `u64::MAX as f32` with a named `const MAX_WARMUP_TIMEOUT_MS: f32 = 3_600_000.0`
(1 hour) in both `de1-wasm` and `de1-ffi` `set_espresso_warmup_timeout`, **and fully
removed the `#[allow(clippy::cast_possible_truncation, cast_sign_loss)]`** — meeting
the literal acceptance ("compiles cleanly without the allow attribute").

The trick to drop the allow without changing behaviour: the old code did
`(seconds*1000).round() … as u64` then `Duration::from_millis`. The `.round()` is
load-bearing — it compensates f32 error so `5.1 s → 5100 ms` (a naive
`from_secs_f32(5.1)` truncates to 5099 ms → 50 ds, a real regression for 0.1-s-step
inputs). New code keeps the round + clamp in the float domain, then **widens to f64
and uses `Duration::from_secs_f64(f64::from(ms)/1000.0)`** — `from_secs_f64`
round-to-nearest-nanosecond recovers the exact integer ms. No float→int cast at all.

Verified equivalence with a throwaway harness across 14 inputs: **identical to the
old code for every finite value ≤ 1 h** (incl. 5.1→51, 5.05→50, 1.2999→13, 30.5→305);
only values > 1 h change — now clamped to 36000 ds instead of the old multi-year
`u64::MAX` duration (the whole point). Non-finite/≤0 → ZERO, unchanged.

Added de1-wasm regression test `set_espresso_warmup_timeout_rounds_and_clamps_to_an_hour`
(decodes the wire deciseconds from the MMR-write JSON): 3.0→30, 5.1→51, 7200→36000.

Verify: `cargo clippy -p de1-wasm -p de1-ffi --all-targets -- -D warnings` clean; test green.

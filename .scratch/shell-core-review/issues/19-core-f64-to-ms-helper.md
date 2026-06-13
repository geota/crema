# 19 — Use the `f64_to_ms` helper everywhere in de1-wasm

- **Status:** ready-for-agent
- **Severity:** P2
- **Area:** core — `de1-wasm/src/lib.rs`
- **Punchlist:** T5-02 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
The finite-check `f64 → i64` ms cast is open-coded at
`de1-wasm:340-357 (signature_for_shot)`, `:751-756 (export_beanconqueror)`,
`:775-779 (export_crema_jsonl)`, `:839-840 (maintenance_readout)` while `f64_to_ms`
(`:626`) is used correctly elsewhere — divergence risk.

## Fix
Route all sites through `f64_to_ms`.

## Acceptance / Verify
- `grep` for open-coded `f64`→`i64` ms casts in `de1-wasm/src/lib.rs` at the four cited line ranges returns no matches.
- All four sites call `f64_to_ms` instead.

## Touched files
- `core/de1-wasm/src/lib.rs:340-357,751-756,775-779,839-840` — replace open-coded casts with `f64_to_ms`

## Comments
<!-- triage + progress notes append below -->

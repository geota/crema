# 19 — Use the `f64_to_ms` helper everywhere in de1-wasm

- **Status:** ✅ done (2026-06-15)
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

### 2026-06-15 — done
Routed every open-coded `if x.is_finite() { x as i64 } else { 0 }` ms cast through
`f64_to_ms`. Six casts across five fns: `signature_for_shot` (both `completed_at`
**and** `duration` — the helper doc even cites this site as the canonical guard),
`export_beanconqueror_main_json`, `export_crema_jsonl`, `maintenance_readout`, and
`import_beanconqueror_json`. The last was **not** in the four cited ranges but is
the same pattern (title says "everywhere") — folded in to kill the divergence risk.

Dropped the now-dead per-site `#[allow(clippy::cast_possible_truncation)]`
attributes (the cast lives in `f64_to_ms`, which keeps its own allow).

**Deliberately NOT converted** (different semantics, not bugs):
- `days_off_roast` — non-finite → `return None`, not `0`; converting would change behaviour.
- `roast_freshness` — `days as i64` is a day count, not a ms timestamp.

Verify: `cargo build/clippy -p de1-wasm --all-targets -- -D warnings` clean;
`cargo test -p de1-wasm` 44/44 (incl. the pinned `signature_for_shot` djb2 digest).
Remaining `as i64` in the file: the helper itself + the two excluded sites above.

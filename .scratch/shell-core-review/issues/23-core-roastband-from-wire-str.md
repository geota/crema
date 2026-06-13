# 23 — Add `RoastBand::from_wire_str` instead of inline match

- **Status:** ready-for-agent
- **Severity:** P3
- **Area:** core — `de1-wasm/src/lib.rs`, `de1-domain/src/bean.rs`
- **Punchlist:** T5-06 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
`de1-wasm:883-901` pattern-matches band wire strings inline rather than
reusing the typed enum — fragile if bands change.

## Fix
Add a `from_wire_str(&str) -> Option<RoastBand>` to `de1-domain::bean`, call it.

## Acceptance / Verify
- `RoastBand::from_wire_str` exists in `de1-domain/src/bean.rs`.
- `de1-wasm:883-901` calls `RoastBand::from_wire_str` instead of inline pattern-matching wire strings.
- No other inline pattern-match on roast band wire strings remains in the codebase.

## Touched files
- `core/de1-domain/src/bean.rs` — add `from_wire_str(&str) -> Option<RoastBand>`
- `core/de1-wasm/src/lib.rs:883-901` — replace inline match with `RoastBand::from_wire_str`

## Comments
<!-- triage + progress notes append below -->

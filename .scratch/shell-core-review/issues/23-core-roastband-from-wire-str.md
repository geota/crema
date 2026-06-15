# 23 — Add `RoastBand::from_wire_str` instead of inline match

- **Status:** ✅ done (2026-06-15)
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

### 2026-06-15 — done
Added `RoastBand::from_wire_str(&str) -> Option<RoastBand>` to `de1-domain::bean`
(the inverse of `as_str`), and replaced the inline wire-string match in **both**
bridges' `roast_freshness`: `de1-wasm:872` and `de1-ffi:397` (the FFI twin wasn't
in the cited range but is the same pattern — acceptance wants none remaining).
Each call site collapses from a 5-line match to
`RoastBand::from_wire_str(&band)?` / `…(band.as_deref()?)?`.

The only `"light" =>` match left in bean/wasm/ffi now lives **inside** `from_wire_str`
itself — the canonical typed parse, which is the point.

**Out of scope:** `crema_profile.rs::Roast::from_classifier` matches the same wire
strings but on a **different enum** (`Roast` = recipe-suitability filter facet,
explicitly "not bean identity") that already encapsulates its own parse. Left as-is.

Added a `de1-domain` round-trip test (`from_wire_str(b.as_str()) == Some(b)` for all
three; `"Light"`/`"medium-dark"`/`""` → None). The existing wasm
`roast_freshness_returns_lowercase_verdict_or_none` still passes (behaviour preserved).

Verify: `cargo clippy -p de1-domain -p de1-wasm -p de1-ffi --all-targets -- -D warnings`
clean; new + existing tests green.

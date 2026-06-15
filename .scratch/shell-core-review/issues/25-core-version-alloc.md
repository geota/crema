# 25 — Avoid per-call alloc in `core_version`

- **Status:** ✅ done (2026-06-15)
- **Severity:** P3
- **Area:** core — `de1-wasm/src/lib.rs`, `de1-ffi/src/lib.rs`
- **Punchlist:** T5-08 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
`env!("CARGO_PKG_VERSION").to_string()` (`de1-wasm:619`, `de1-ffi:466`)
allocates each call.

## Fix
Return the `&'static str` internally; `.to_owned()` only at the bridge boundary.

## Acceptance / Verify
- The internal implementation of `core_version` returns or uses `&'static str` (no `.to_string()` on the `env!` macro result in the hot path).
- The bridge boundary performs `.to_owned()` / `.to_string()` exactly once, only where the binding requires an owned `String`.

## Touched files
- `core/de1-wasm/src/lib.rs:619` — defer `.to_owned()` to bridge boundary
- `core/de1-ffi/src/lib.rs:466` — defer `.to_owned()` to bridge boundary

## Comments
<!-- triage + progress notes append below -->

### 2026-06-15 — done
Added a private `const CORE_VERSION: &str = env!("CARGO_PKG_VERSION");` to each
bridge; `core_version()` now returns `CORE_VERSION.to_owned()` instead of
`env!(…).to_string()`. The internal value is `&'static str`; the single
`.to_owned()` happens only at the binding boundary — matching the acceptance.

Note: the **per-call allocation at the boundary is irreducible** — both
wasm-bindgen and uniffi return owned `String` (neither ABI can hand back a
borrowed `&'static str`), so the exported fn must allocate one `String` per call.
The win is structural: the borrowed static is now named and reusable internally,
and there's no `.to_string()` on the `env!` result. Kept each bridge reporting its
**own** `CARGO_PKG_VERSION` (versions are per-crate, all `0.1.0`) rather than
hoisting to a shared crate, which would change which crate's version is reported.

Verify: `cargo clippy -p de1-wasm -p de1-ffi --all-targets -- -D warnings` clean;
tests green (de1-wasm 46, de1-ffi 32).

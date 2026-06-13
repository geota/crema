# 25 — Avoid per-call alloc in `core_version`

- **Status:** ready-for-agent
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

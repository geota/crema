# 20 — Align scale-command receivers `&mut self` → `&self` in WASM

- **Status:** ✅ done (2026-06-15) — 6 of 7 aligned; `tare_scale` is a justified exception
- **Severity:** P2
- **Area:** core — `de1-app/src/lib.rs`, `de1-wasm/src/lib.rs`
- **Punchlist:** T5-03 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
`tare_scale` (`de1-wasm:1216`) and `set_scale_volume/standby/
flow_smoothing/anti_mistouch/mode/auto_stop` take `&mut self` in WASM but `&self` in FFI;
none mutate `CremaBridge` state.

## Fix
Change to `&self` to match FFI and correctness semantics.

## Acceptance / Verify
- `tare_scale` and all `set_scale_*` methods in `de1-wasm/src/lib.rs` take `&self` (not `&mut self`).
- The WASM build compiles cleanly with no mutation errors.

## Touched files
- `core/de1-wasm/src/lib.rs:1216` — `tare_scale` receiver
- `core/de1-wasm/src/lib.rs` — all `set_scale_*` method receivers

## Comments
<!-- triage + progress notes append below -->

### 2026-06-15 — done (root-caused to de1-app; tare is a real exception)
The premise ("none mutate") held for 6 of the 7, but **the fix isn't in WASM** —
it's one layer down. The WASM bridge owns `core: CremaCore` directly, so its
method receiver is forced by the inner `CremaCore` method's receiver; the FFI
bridge is `&self` only because it wraps the core in `Mutex<CremaCore>` and takes
`&mut` through the lock. So flipping the WASM receiver alone fails to compile.

Traced every layer:
- `de1-app::CremaCore::set_scale_{volume,standby,flow_smoothing,anti_mistouch,mode,auto_stop}`
  borrow `&self.scale` and call the scale driver's `set_*_command(&self)` /
  `select_mode_command(&self)` — **pure command builders, no mutation**. They were
  needlessly `&mut self`. → changed to `&self` (the true root cause), which lets
  the 6 WASM bridge methods become `&self`.
- `de1-app::CremaCore::tare_scale` → `push_tare_command` → `&mut self.scale` →
  `Scale::tare(&mut self)`, which **bumps the Decent scale's command-sequence
  counter** (`de1-scale/decent_scale.rs::tare(counter)`). Genuine mutation.
  `tare_scale` stays `&mut self` in de1-app **and** the WASM bridge. FFI's `&self`
  tare is possible only via its `Mutex`; matching it in WASM would mean adding a
  `RefCell` for no benefit (JS is single-threaded). Left `&mut self` + a doc note
  on the WASM `tare_scale` explaining the divergence.

Also dropped three now-`unused_mut` `let mut core` in de1-app's "without-a-scale"
tests (they only call the now-`&self` setters).

FFI surface unchanged (bridge method sigs identical) → no binding regen. wasm
method TS signatures unchanged (`&mut`/`&` is invisible to JS) → no web regen.

Verify: `cargo clippy -p de1-app -p de1-wasm -p de1-ffi --all-targets -- -D warnings`
clean; tests 219 (de1-app) + 44 (de1-wasm) + 32 (de1-ffi), all green.

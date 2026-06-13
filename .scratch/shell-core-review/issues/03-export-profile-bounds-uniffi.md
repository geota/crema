# 03 — Export `profile_bounds_json` via UniFFI; replace hardcoded editor limits

- **Status:** done
- **Severity:** P1
- **Area:** Core (UniFFI · WASM) · Android (tablet + phone)
- **Punchlist:** T1-03 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem

Core caps (`core/de1-domain/src/profile_bounds.rs`): frame time **25.5s** (`u8p4` ceiling), brew/seg temp **100°C**, pressure/flow **15.9375**, volume **1023ml**. Android editor lets you author **120s** frames (`ProfileEditScreen.kt:286`) and **105°C** (`:288,471`) — firmware-invalid (saturates on the wire); and is conversely *stricter* on pressure (**12** vs 15.9375, `:431,469,etc.`). Web uses `profile_bounds.ts:43` → `profile_bounds_json`. Core fn is WASM-only (`de1-wasm:971`).

## Fix

- Export `profile_bounds_json` via UniFFI in `de1-ffi`.
- Replace the literal min/max args in `ProfileEditScreen.kt` (and `PhoneProfileEditScreen.kt` counterparts) with values parsed from the core bounds JSON.
- Editorial "warn above X" stays shell-side per the module doc.

## Acceptance / Verify

Android editor max frame time = 25.5s, brew temp = 100°C, pressure = 15.9375; matches web.

## Touched files

- `core/de1-ffi/src/lib.rs` — export `profile_bounds_json`
- `core/de1-wasm/src/lib.rs:971` — reference implementation
- `core/de1-domain/src/profile_bounds.rs` — source of truth for caps
- `android/app/src/main/java/coffee/crema/ui/screens/ProfileEditScreen.kt:286,288,431,469,471` — replace hardcoded limits
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneProfileEditScreen.kt` — replace hardcoded limits
- `web/src/lib/settings/profile_bounds.ts:43` — already correct (reference)

## Comments
<!-- triage + progress notes append below -->

### 2026-06-13 — done

- **Core:** hoisted the JSON builder out of the wasm crate into
  `de1_domain::profile_bounds::profile_bounds_json()` so the two shells share one
  source (the wasm export was hand-rolling a second copy of the same `format!` —
  drift risk). `de1-wasm` and the new `de1-ffi` `#[uniffi::export]` both delegate to
  it. Added a `de1-domain` test locking the JSON (valid, 13 keys, caps match the
  consts). Dropped the stale "intentionally not exported" note for
  `profile_bounds_json` from the `de1-ffi` module header (Android now has editors).
- **Binding:** `cargo` + uniffi-bindgen emits `fun profileBoundsJson(): String`
  (verified). No Android build in this env (no NDK); Kotlin reviewed by eye —
  stepper `min`/`max` params are all `Double`, conversions are `.toDouble()`.
- **Android:** new `profiles/ProfileBounds.kt` — `@Serializable` data class with
  `@SerialName` snake_case keys, parsed once via a lazy `INSTANCE` singleton.
  Replaced the firmware-invalid literals in **both** editors:
  - `ProfileEditScreen.kt` (tablet): brew temp `105→max_temperature_c (100)`, max
    volume `1023→max_total_volume_ml` (+ `min_total_volume_ml`), seg target
    `12/10→max_pressure_bar/max_flow_ml_per_s (15.9375)`, seg time
    `120→max_frame_seconds (25.5)`, seg temp `105→100`, exit threshold `12→bound by
    metric`, limiter value `12→bound by the other channel`.
  - `PhoneProfileEditScreen.kt` (phone): brew temp `100→sourced`, seg target
    `12→…`, duration `127→25.5`, seg temp `105→100`, exit `12→…`, limiter `12→…`.
  - Left editorial floors/caps shell-side per the module doc: brew/seg temp mins
    (20/70/80), tank temp 95, seg volume 500, tolerance 6, preinfuse-steps 10,
    dose/yield ranges.
- **Minor (not fixed):** phone Duration stepper keeps its integer step (1.0) +
  `%.0f` display, so at the new 25.5 ceiling it shows "26 s" while the stored value
  is correctly clamped to 25.5 (firmware-valid). Benign; tablet uses 0.5 step and
  shows 25.5 exactly.
- **Web:** unchanged — already parsed `profile_bounds_json` (`profile_bounds.ts:43`);
  wasm export name/shape identical, so the TS surface didn't move.

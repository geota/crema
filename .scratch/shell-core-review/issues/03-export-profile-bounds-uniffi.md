# 03 — Export `profile_bounds_json` via UniFFI; replace hardcoded editor limits

- **Status:** ready-for-agent
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

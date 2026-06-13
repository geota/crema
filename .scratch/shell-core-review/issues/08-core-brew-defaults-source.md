# 08 — Add core brew-defaults source; both shells read it

- **Status:** ready-for-agent
- **Severity:** P2
- **Area:** Core (UniFFI · WASM) · Android · Web
- **Punchlist:** T1-08 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem

Dose 18g / ratio 2.0 / temp 93°C / preinf 8s are hardcoded independently in web (`settings/store.svelte.ts:226-228`) and Android (`MainViewModel.kt:243-246` + fallback literals `:833,835`). Core has a `BrewDefaults` struct (`crema_profile.rs:159`) but supplies no default *values*.

## Fix

- Add `default_brew_defaults_json()` to core (`de1-domain`).
- Export via both WASM (`de1-wasm`) and UniFFI (`de1-ffi`) bindings.
- Replace the hardcoded literals in both shells by reading from this fn.

## Acceptance / Verify

Changing the defaults in core propagates to both shells without touching shell code; no literal `18`, `2.0`, `93`, `8` default constants remain in `settings/store.svelte.ts` or `MainViewModel.kt`.

## Touched files

- `core/de1-domain/src/crema_profile.rs:159` — add default values to `BrewDefaults` struct; add `default_brew_defaults_json()`
- `core/de1-ffi/src/lib.rs` — export `default_brew_defaults_json`
- `core/de1-wasm/src/lib.rs` — export `default_brew_defaults_json`
- `web/src/lib/settings/store.svelte.ts:226-228` — read from core instead of literals
- `android/app/src/main/java/coffee/crema/ui/MainViewModel.kt:243-246,833,835` — read from core instead of literals

## Comments
<!-- triage + progress notes append below -->

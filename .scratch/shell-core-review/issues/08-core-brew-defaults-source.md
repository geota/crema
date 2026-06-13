# 08 — Add core brew-defaults source; both shells read it

- **Status:** done
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

### 2026-06-13 — done

- **Core:** added `impl Default for BrewDefaults` (dose 18 / ratio 2.0 / brew
  93 °C / preinf 8 s — the numbers both shells hardcoded) + a free fn
  `default_brew_defaults_json()` serialising it (camelCase keys `doseG`,
  `ratio`, `brewTempC`, `preinfusionS`). Re-exported at crate root; exported via
  wasm `defaultBrewDefaults` and ffi `#[uniffi::export] default_brew_defaults_json`.
  Domain test locks the keys/values + struct round-trip. Binding verified.
- **Web** (`settings/store.svelte.ts`): a lazily-memoised `brewDefaults()` parses
  the wasm `defaultBrewDefaults` once; `DEFAULT_SETTINGS` exposes the four fields
  as **getters** off it. Lazy/never-at-module-scope per the `$lib/profiles/bounds.ts`
  rule (wasm only callable after `loadCore()`); the getters fire when the settings
  store is first constructed at render time. No `18`/`2.0`/`93`/`8` literal remains.
- **Android:** new `profiles/BrewDefaults.kt` (`@Serializable`, lazy `INSTANCE`
  parsed from `defaultBrewDefaultsJson()`). `MainUiState`'s four defaults and
  `saveQuickPreset`'s fallbacks now read `BrewDefaults.INSTANCE.*` (yield fallback
  = `doseG * ratio`, was `36f`). `MainUiState()` is constructed only in the VM
  (line ~448) and the app has **no `@Preview`s**, so the FFI-in-default-arg is
  always on-device — no host-JVM/preview `UnsatisfiedLinkError` risk.
- **Verify:** `cargo test` green; `npm run check` 0 errors. Android reviewed by
  eye (no NDK here): `BrewDefaults.INSTANCE.*` are `Float`, matching the
  `Float`-typed fields/fallbacks; uniffi auto-loads the native lib on first call.

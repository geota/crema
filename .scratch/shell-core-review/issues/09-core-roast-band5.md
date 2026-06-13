# 09 — Add `roast_band5` to core + both bindings

- **Status:** done
- **Severity:** P2
- **Area:** Core (UniFFI · WASM) · Android · Web
- **Punchlist:** T1-09 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem

Identical 5-bucket roast-band display label mapping is hand-rolled twice — web `bean/model.ts:309-317` and Android `BeanFormat.kt:38`. Not in core.

## Fix

- Add `roast_band5` to `de1-domain` next to `roast_band`.
- Export via WASM (`de1-wasm`) and UniFFI (`de1-ffi`).
- Replace both shell copies.

## Acceptance / Verify

`grep -rn "roast_band5\|roastBand5" android/ web/src` shows only call sites, no inline mapping definitions; the 5-bucket mapping exists solely in `de1-domain`.

## Touched files

- `core/de1-domain/src/bean.rs` — add `roast_band5` fn next to `roast_band`
- `core/de1-ffi/src/lib.rs` — export `roast_band5`
- `core/de1-wasm/src/lib.rs` — export `roast_band5`
- `web/src/lib/bean/model.ts:309-317` — replace inline mapping with core call
- `android/app/src/main/java/coffee/crema/beans/BeanFormat.kt:38` — replace inline mapping with core call

## Comments
<!-- triage + progress notes append below -->

### 2026-06-13 — done

Added `de1_domain::roast_band5(level: i32) -> &'static str` next to `roast_band`
(clamps 1..10, returns the exact display labels both shells used) + a domain test
covering every bucket and out-of-range clamping. Re-exported at the crate root;
exported via `de1-wasm` `roast_band5` and `de1-ffi` `#[uniffi::export] roast_band5`
(both `Option<i32> -> Option<String>`, matching `roast_band`). Binding verified:
`fun roastBand5(level: Int?): String?`.

- **Android** `BeanFormat.kt`: `roastBand5` is now a one-line delegate to
  `coreRoastBand5` (was a 6-arm `when`).
- **Web** `bean/model.ts`: `roastBand5` delegates to `wasmRoastBand5(Math.round(level))`,
  keeping only the `'—'` placeholder for null shell-side (rebuilt the wasm pkg so
  the `.d.ts` declares it).

Acceptance met — the 5-bucket mapping lives solely in `de1-domain`; both shell
functions are thin wrappers, all other refs are call sites. `cargo test` green;
`npm run check` 1203 files / 0 errors.

# 09 — Add `roast_band5` to core + both bindings

- **Status:** ready-for-agent
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

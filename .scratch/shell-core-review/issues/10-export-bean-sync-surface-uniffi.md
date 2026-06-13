# 10 — Proactively export bean/roaster sync surface via UniFFI

- **Status:** done
- **Severity:** P2
- **Area:** Core (UniFFI · WASM) · Android
- **Punchlist:** T1-10 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem

`reconcile_beans`/`reconcile_roasters`/`signature_for_bean`/`signature_for_roaster`/`coerce_bean`/`coerce_roaster` are WASM-only. Web uses them (`services/bean-sync.ts:287,371`); Android bean sync is "web-only for now" (`VisualizerSync.kt:33-36`). When Android adds bean sync it will be forced to re-port.

## Fix

Export the six fns via UniFFI now so the future Android path routes through core from day one.

## Acceptance / Verify

All six fns appear in the UniFFI bindings (`de1-ffi`); no implementation logic needs to be in Android Kotlin when bean sync is wired up.

## Touched files

- `core/de1-ffi/src/lib.rs` — export `reconcile_beans`, `reconcile_roasters`, `signature_for_bean`, `signature_for_roaster`, `coerce_bean`, `coerce_roaster`
- `web/src/lib/services/bean-sync.ts:287,371` — reference usage
- `android/app/src/main/java/coffee/crema/visualizer/VisualizerSync.kt:33-36` — future call site

## Comments
<!-- triage + progress notes append below -->

### 2026-06-13 — done

Exported all six via `#[uniffi::export]` in `de1-ffi`, each a one-line delegate
mirroring the wasm export of the same name: `reconcile_beans` /
`reconcile_roasters` (→ `Result<String, CremaError>` via `crema_err`),
`signature_for_bean` / `signature_for_roaster` (→ `String`), `coerce_bean` /
`coerce_roaster` (→ `Option<String>`, taking native `i64` `now_unix_ms` instead
of the wasm `f64`). Bindgen confirms all six: `reconcileBeans`,
`reconcileRoasters`, `signatureForBean`, `signatureForRoaster`, `coerceBean`,
`coerceRoaster`. Latent — no Android caller yet (`VisualizerSync.kt` bean sync
stays web-only); when wired, the Android path routes through core with zero
re-ported logic. No shell changes.

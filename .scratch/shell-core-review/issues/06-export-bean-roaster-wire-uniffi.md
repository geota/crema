# 06 — Export bean/roaster wire converters via UniFFI; delete Android hand-built wire

- **Status:** ready-for-agent
- **Severity:** P2
- **Area:** Core (UniFFI · WASM) · Android
- **Punchlist:** T1-06 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem

`bean_to_wire`/`bean_from_wire`/`roaster_to_wire`/`roaster_from_wire`/`roast_level_to_wire`/`roast_level_from_wire` are WASM-only. Web uses them (`bean/visualizer-sync.ts:176-212`); Android hand-builds the wire object (`visualizer/WireShot.kt:93-107`) and inline-bean PATCH (`VisualizerSync.kt:349-358`), already divergent (emits `roastLevel`/`roastedOn` as `JsonNull`).

Note: lower urgency until Android bean-sync ships, but the wire is already emitted today, so the divergence is live.

## Fix

- Export the six fns (`bean_to_wire`, `bean_from_wire`, `roaster_to_wire`, `roaster_from_wire`, `roast_level_to_wire`, `roast_level_from_wire`) via UniFFI in `de1-ffi`.
- Replace the Android hand assembly in `WireShot.kt` and `VisualizerSync.kt`.

## Acceptance / Verify

Android emitted wire JSON matches the web-emitted wire JSON field-for-field (including `roastLevel`/`roastedOn`); no hand-assembly code remains in `WireShot.kt:93-107` or `VisualizerSync.kt:349-358`.

## Touched files

- `core/de1-ffi/src/lib.rs` — export the six wire converter fns
- `android/app/src/main/java/coffee/crema/visualizer/WireShot.kt:93-107` — delete hand-built wire assembly
- `android/app/src/main/java/coffee/crema/visualizer/VisualizerSync.kt:349-358` — delete inline-bean PATCH assembly
- `web/src/lib/bean/visualizer-sync.ts:176-212` — reference implementation

## Comments
<!-- triage + progress notes append below -->

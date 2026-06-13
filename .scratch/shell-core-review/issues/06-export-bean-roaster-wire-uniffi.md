# 06 — Export bean/roaster wire converters via UniFFI; delete Android hand-built wire

- **Status:** done (UniFFI exports; Android hand-wire replacement deferred — see Comments)
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

### 2026-06-13 — exports done; Android replacement deferred (architecturally premature)

**Done:** exported all six via `#[uniffi::export]` in `de1-ffi`, each mirroring
the wasm export of the same name (native `i64` `now_unix_ms`, not the wasm `f64`):
`bean_to_wire` / `bean_from_wire` / `roaster_to_wire` / `roaster_from_wire`
(→ `Result<String, CremaError>`), `roast_level_to_wire(Option<f64>) ->
Option<String>`, `roast_level_from_wire(Option<String>) -> Option<i32>`. Bindgen:
`beanToWire`, `beanFromWire`, `roasterToWire`, `roasterFromWire`,
`roastLevelToWire`, `roastLevelFromWire`. (Same proactive-export approach as
issue 10 — the shared converter is now available to Android.)

**Deferred — the hand-wire replacement isn't a swap, it's blocked on the data
model:** `WireShot.kt:93-107` builds the `bean` sub-object from a *flat label*
(`shot.beanName`, plus `beanShort`/`roasterName`), NOT a full `Bean` — which is
exactly why it emits `roastLevel`/`roastedOn` as `JsonNull`. `VisualizerSync.kt:349-358`
likewise splits a `"Roaster · Name"` string into `bean_brand`/`bean_type`. The
core `bean_to_wire` needs a full `Bean` JSON; Android doesn't carry one to the
shot-wire site until bean-sync ships (it stores only the denormalized label on
the shot). So fixing the divergence means first threading the full `Bean` record
through to wire-build time — the same prerequisite the issue flags ("lower urgency
until Android bean-sync ships"). Replacing blindly would change a **live Visualizer
upload** I can't test on-device. Left for when Android gains the shot→Bean link;
the export is in place so that work is a thin wiring step.

### 2026-06-13 — fake-data verification of the converters (de1-ffi tests)

Confirmed `StoredShot` (`history/ShotHistory.kt`) carries only `beanName: String?`
(a flat `"Roaster · Name"`) — no `beanId`/`roastLevel`/`roastedOn` — so the data
the wire needs genuinely isn't there (this is the documented StoredShot-migration
debt; building/running the app can't conjure it). To prove the **core converters**
are correct (i.e. the gap is purely Android plumbing), added a device-independent
round-trip test through the exported FFI fns with realistic fake bean/roaster data:
`bean_to_wire → bean_from_wire` preserves `name` + `roastLevel` (7) + `roastedOn`
("2026-05-20") — the exact fields the Android shot wire drops; plus roaster
round-trip, `coerce_bean` tolerance, and `roast_level_to/from_wire`. All green
(`de1-ffi` 32 tests). So once Android threads a full `Bean` to the wire site, the
fix is a thin call to `beanToWire` — the shared logic already round-trips losslessly.

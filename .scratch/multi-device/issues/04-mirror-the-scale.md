# 04 — Mirror the scale, not just the DE1 (WEIGHT card is always "—")

- **Status:** ready-for-agent
- **Severity:** P1
- **Area:** Android (proxy · RelayHub) + Core (snapshot rule)
- **Depends on:** none

## Problem

A secondary's **WEIGHT card is permanently "—"** (visible in every mirror screenshot).
The primary's roster only advertises the DE1:

```kotlin
roster = { ble.connectedAddress?.let { listOf(DeviceInfo(it, "DE1", "de1", "CONNECTED")) } ?: emptyList() }
```

So the secondary's `ScaleBleManager` never gets a scan-match → never `Attach`es → never
observes the scale. The weight notifications **are** being tapped on the primary
(`TappingBleTransport` tees *every* observe), they're just never offered to a secondary.
This is the documented "DE1-only mirror for now."

## Design

The protocol already anticipates this (`../M1-PROTOCOL.md` §2): the wire carries the
scale's **advertised name**, not scale UUIDs — the secondary re-derives the codec/UUIDs
locally via `bridge.connectScale(name)`. So:

1. **Roster includes the scale** when the primary holds one:
   `scale.connectedAddress` + `scale.connectedName` → `DeviceInfo(addr, name, "scale", "CONNECTED")`.
2. The secondary's `ScaleBleManager` scan-matches the name (its existing `isBookooName`/
   `isAcaiaName`/… rules), `Attach`es, and `bridge.connectScale(name)` re-derives the
   scale's service/char UUIDs locally → it observes the proxied weight stream.
3. **Snapshot rule must exclude the scale-weight char.** `isSnapshotChar` currently only
   excludes `SHOT_SAMPLE`:
   ```kotlin
   isSnapshotChar = { _, char -> char != De1Uuids.SHOT_SAMPLE }
   ```
   Scale weight is a **counted stream** too — snapshotting it would double-count in the
   core (same hazard, `../M1-PROTOCOL.md` §5). Extend the predicate to also exclude the
   connected scale's weight char (the primary knows it once `connectScale` resolves), so
   weight stays **live-only**. Scale *config/identity* chars (battery, serial) may still
   snapshot.

### Handoff interaction (#01/#07)

The scale is a separate BLE device; when the radio moves, the **new primary must
connect to both** the DE1 and the scale. The taker's `connect()` already scans for both
(its scale manager runs); just ensure the scale is re-acquired on `switchToPrimary`.

## Fix (code, sketch)

- `MainViewModel.startPrimaryMode`: roster lambda adds the scale `DeviceInfo` when
  `scale.connectedAddress != null`; pass the scale weight char into `isSnapshotChar`
  (capture the resolved char from `bridge.scaleUuids()` once connected).
- The secondary path needs no change beyond the roster surfacing the scale — the
  managers + `connectScale` re-derivation already run unchanged.
- Verify the scale read-path: a secondary issues no scale writes (T1a read-only holds),
  so the scale config queries are suppressed — its identity must come from the snapshot.

## Acceptance / Verify

2-emulator with a scale in the replay capture (e.g. a `bookoo-*.jsonl`): the secondary's
**WEIGHT + scale-derived flow populate live** and match the primary; weight is not
double-counted (sample counts equal). Without a scale, roster stays DE1-only (no
regression).

## Touched files

- `android/app/src/main/java/coffee/crema/ui/MainViewModel.kt` — roster + `isSnapshotChar`
- `android/app/src/main/java/coffee/crema/ble/proxy/RelayHub.kt` — (verify scale-weight not cached)
- capture fixture with a scale stream for the test

## Comments
<!-- triage + progress notes append below -->

**2026-06-20 — attempted, then reverted (genuinely hardware-gated).** Wrote the
relay-side foundation and confirmed the design is sound, but **reverted rather than
commit unvalidated** — there's no scale in the replay capture and no scale-replay
harness (`replayRoute` has no SCALE_WEIGHT path), so neither the live weight nor the
counted-stream **double-count** (the whole risk) can be verified on the emulator;
only no-regression is checkable. Shipping it blind is exactly what this issue's note
warns against. Re-do it in a hardware (or scale-replay) session — the worked-out plan:

- **`ScaleBleManager`:** expose `connectedName` (= `advertisedName` when connected)
  and `weightNotifyChar` (= `weightNotifyUuid`).
- **`startPrimaryMode` roster:** `buildList { DE1; scale.connectedAddress?.let { add(DeviceInfo(addr, scale.connectedName ?: "Scale", "scale", "CONNECTED")) } }`.
- **`isSnapshotChar`:** `char != De1Uuids.SHOT_SAMPLE && char != scale.weightNotifyChar`
  (keep the counted weight stream live-only — the double-count fix).
- **Secondary attach (the messy bit):** a mirror must attach the primary's scale,
  but a *blind* `connectScale()` on a scaleless primary leaves the scale stuck on
  "Scanning…" (a no-scale regression). Do it **roster-driven**: expose
  `ProxyTransport.roster` and, on a secondary, `connectScale()` only once a
  `kind == "scale"` entry appears (no scan → "Not paired" when the primary has none).
- **Validate (needs a scale or a scale-replay):** secondary WEIGHT + scale-flow
  populate live and match the primary; sample counts equal (no double-count);
  scaleless primary → roster stays DE1-only, no "Scanning…" regression.

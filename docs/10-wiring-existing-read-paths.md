# 10 — Wiring Up Existing Read Paths

**Status:** ready to implement
**Companion:** the read-path audit (Part 1) and `11-new-read-paths-and-ui.md`

## Purpose

Make every screen show **real data**. This doc covers only UI elements that
*already exist* but today display hardcoded, synthetic, or inert values. The
job is to connect each one to data the core already produces — or can produce
with a small, well-scoped change. **No new screens or components** (those are
doc 11).

## Non-goals

- New screens / components / information surfaces — see doc 11.
- The DE1 **control** path (Start/Stop extraction, Quick-Sheet steppers writing
  to the machine, scale timer start/stop). Those are *write* paths; this doc is
  read-only. Track control wiring separately.
- New BLE characteristics, except the DE1 `Version` read needed for D4.

## The wiring pattern

Every read path follows the same spine. For a value the core already decodes:

1. **Protocol** — the decode already exists in `core/de1-protocol/src/`.
2. **`Event` / `Source`** — `core/de1-app/src/event.rs`. Add fields to an
   existing `Event` variant, or a new variant / `Source`. Both enums are
   `#[non_exhaustive]` by design.
3. **Orchestrator** — `core/de1-app/src/lib.rs` decodes the `Source` and emits
   the `Event`.
4. **Bridge** — `core/de1-wasm` (`CremaBridge`). `on_notification` already
   routes by `NotificationSource`; only *new* query commands need a new method.
5. **typeshare + wasm** — regenerate the TS types (`core/generate-bindings.sh`)
   and rebuild the wasm bundle into `web/src/lib/wasm/`.
6. **`applyEvent`** — `web/src/lib/state/ui-state.svelte.ts` folds the event
   into `UiSnapshot`.
7. **UI** — the component reads the `UiSnapshot` field.

Many items below stop at step 7 because the data is *already* in `UiSnapshot`
(e.g. `scaleTimerMs`, `scaleBatteryPercent`) — the UI just never reads it.

## Work items

### A. Brew dashboard

- **A1 — Steam temp.** `BrewDashboard.svelte:305` hardcodes `148 °C`.
  `ShotSample.steam_temp` is already decoded (`shot_sample.rs:39,86`) but
  `Event::Telemetry` drops it. Add `steam_temp: f32` to `Event::Telemetry`,
  carry it through `TelemetrySample` / `UiSnapshot.latestTelemetry`, point the
  readout at it.
- **A2 — Group temp.** `BrewDashboard.svelte:304` hardcodes `93.2 °C`. The DE1
  "group" temperature is `ShotSample.mix_temp` (decoded, not in the event).
  Add `mix_temp` to `Event::Telemetry` the same way. (`head_temp` is already in
  the event and shown as a channel readout — confirm which the design wants.)
- **A3 — LiveChart goal line.** `LiveChart.svelte:46-54` `goalAt` is a synthetic
  3→9 bar ramp. Replace it with the **active profile's** target curve — the
  profile is already in `profileStore` and `curve.ts` already samples it.
  Web-only change.
- **A4 — PhaseIndicatorCard.** `PhaseIndicatorCard.svelte` uses a fixed 32 s
  synthetic four-phase model. Drive it from `Event::ShotFrameChanged.frame`
  (already an event) plus the active profile's frames.
- **A5 — Grinder name.** `BeanContextCard.svelte:79` hardcodes `"Niche Zero"`.
  Either hide the grinder line until a real field exists, or add a `grinder`
  field to the bean model (`$lib/bean`) and surface that.

### B. History

- **B1 — Per-shot dose.** `history/model.ts:80-87` `ratioLabel` divides yield by
  a hardcoded **18 g**. Capture the brew dose at `ShotCompleted` time, persist
  it on the `HistoryStore` record, and divide by the real value.
- **B2 — Range selector.** `history/+page.svelte:37` — "Last 30 days / All time"
  is UI-only. Make it actually filter the list.

### C. Profiles

- **C1 — Profile dose.** The DE1 profile format carries
  `profile_grinder_dose_weight`; the core `Profile` struct has no dose field, so
  every built-in card shows a hardcoded `18 g` (`profiles/model.ts:364`). Add a
  `dose` field to `core/de1-domain/src/profile.rs`, round-trip it through
  `import_v2_json` / `export_v2_json`, surface the real value.
- **C2 — "Last used".** `store.svelte.ts:198` only ever yields `"just now"` /
  `"never used"`. Persist a real timestamp when a profile is loaded on Brew;
  format it relatively.

### D. Settings — consume the write-only preferences

Every `lib/settings` preference except `theme` persists to `localStorage` and is
read back **only by the settings UI itself**. Nothing else consumes them. Wire
the consumers:

- **D1 — Units.** Route `weightUnit` / `tempUnit` / `pressureUnit` /
  `volumeUnit` through one shared formatting helper used by every Brew, History,
  and Scale readout.
- **D2 — Brew defaults.** `defaultDoseG` / `defaultRatio` seed the Quick-Sheet
  dose helper and yield-ratio defaults.
- **D3 — Telemetry options.** `telemetryRateHz`, `showFlowCurve`,
  `showPuckResistance`, `smoothPressure`, `showDebugPanel` — consumed by
  `LiveChart` and the state layer.
- **D4 — Machine card.** `MachineSection.svelte:165-166` shows `—` for Firmware
  and BLE. Decode the DE1 `Version` characteristic (`firmware.rs` already
  decodes it) via a new `Source::De1Version` + `Event::Firmware`; take the BLE
  MAC/id from the Web Bluetooth device object in the transport layer.

### E. Water & maintenance

`WaterSection.svelte:35-61` (via `StMaintenanceCard`) hardcodes filter 74 %,
descale 142 L, backflush 48 hr, and all dates. The DE1 has no cumulative-volume
counter — but de1app derives one by integrating flow (`de1_de1.tcl:570-628`).

- **E1 — App-side water accumulation.** Integrate group flow × Δt across each
  shot / flush / hot-water session, persist cumulative litres. Derive filter %,
  descale-L, backflush-hr from the persisted counters and user-set intervals.
  Wire `StMaintenanceCard` to the persisted state.
- **E2 — Refill-soon.** `Event::WaterLevel.refill_threshold_mm` is already in
  the event payload. Surface a "refill soon" cue when `level_mm` approaches it.

### F. Decoded but unshown — an existing UI slot is waiting

- **F1 — Scale page.** `UiSnapshot` already carries `scaleTimerMs` and the
  device flow rate; the polished `scale/+page.svelte` shows neither. Display
  them.
- **F2 — Scale beeper volume.** The volume `RangeCapability` and `scaleVolume`
  are already wired in state; the polished Scale page dropped the control.
  Restore it.

## Ordering

1. Core changes: A1/A2 (`Event::Telemetry` fields), C1 (`Profile.dose`), D4
   (`Source::De1Version` + `Event::Firmware`).
2. Regenerate typeshare + rebuild the wasm bundle.
3. `applyEvent` folding for the new fields/events.
4. Web-only items: A3, A4, A5, B1, B2, C2, D1–D3, E1, E2, F1, F2.

## Acceptance

- `cargo test` / `cargo build` clean for the touched core crates; wasm bundle
  rebuilt and committed.
- `pnpm check` → 0 errors / 0 warnings; `pnpm build` clean.
- No hardcoded telemetry constants remain in `BrewDashboard.svelte`.
- The History ratio reflects the recorded dose; profile cards show real dose.
- Every settings preference has at least one consumer outside the settings UI.
- Work on a branch; commit logical checkpoints.

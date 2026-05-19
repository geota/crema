# 11 — New Read Paths & UI Additions

**Status:** READ side ready to implement; UI side deferred
**Companion:** the read-path audit (Part 2) and `10-wiring-existing-read-paths.md`

## Purpose

Information surfaces present in de1app and/or DSx2 but **absent from Crema** —
they have no current UI at all. This doc has two halves:

- **READ side** — acquire the data: decode the BLE characteristics / MMR
  registers, add core `Source`s / `Event`s, expose the values in `UiSnapshot`.
  **Implement this now.**
- **UI side** — new screens / components to display it. **Do NOT implement
  yet** — listed here only so the read side is shaped to serve it.

The split matters: the read side is pure plumbing (core + state, no visual
design), so it can land and be tested independently of the UI work.

## READ side — implement now

Follow the wiring pattern in doc 10 §"The wiring pattern", stopping at step 6:
the value lands in `UiSnapshot` (or a dedicated diagnostics struct) and **no
component reads it yet**. Add core decode tests for every new event.

### R1 — MMR diagnostic registers

`core/de1-protocol/src/mmr.rs` already has `MmrRegister`, `MmrReadReply`,
`MMR_PACKET_LEN`. MMR is **request/reply**: the shell writes a read request,
the DE1 answers on the MMR notify characteristic.

- Add `Source::De1MmrRead` (the MMR notify characteristic) and
  `WriteTarget::De1MmrRequest` (`WriteTarget` is `#[non_exhaustive]` and its
  doc already says "MMR targets arrive later").
- Add `Event::MmrValue { register: MmrRegister, value: u32 }` (or a typed
  per-register event).
- `CremaBridge`: a `read_mmr(register)` method that returns a `CoreOutput`
  whose command is the read request — same shape as `query_scale_settings`.
- Orchestrator decodes the reply via `MmrReadReply` and emits the event.
- Registers worth reading: serial number, firmware build number, machine
  model / variant, CPU board revision, fan threshold, tank-temp threshold,
  heater voltage, GHC installed / mode, refill-kit detected, steam flow &
  high-flow start, phase-1/2 flush flow rates, calibration flow multiplier,
  cup-warmer temp, USB-charger state.
- Expose the decoded registers in a `UiSnapshot.de1Diagnostics`-style struct.

### R2 — DE1 firmware / API version

(Shared with doc 10 D4 — if D4 lands first, R2 is done.) `Source::De1Version`
+ `Event::Firmware`; `firmware.rs` already decodes the `Version` characteristic.

### R3 — Calibration values

`core/de1-protocol/src/calibration.rs` already has `Calibration`, `CalCommand`,
`CalTarget`. Add a `Source` / `Event` for the calibration characteristic so
pressure / flow / temperature calibration (factory vs. current) is readable.

### R4 — Derived metrics

- **Resistance** — `pressure / flow²`, the de1app/DSx puck-resistance metric.
  Compute it in the core (alongside telemetry) or in the state layer from the
  existing telemetry sample; add it to `TelemetrySample`.
- **Cumulative water** per session — if doc 10 E1 lands, reuse it; otherwise
  scope the flow-integration here.

### R5 — Machine error substates

`SubState` already decodes the ~18 DE1 error variants (`ErrorNoAc`,
`ErrorTSensor`, …). Add a typed mapping from error substate → readable text and
surface it as a `UiSnapshot.machineError` field (`null` when healthy). No
banner / screen yet — just the field.

### R6 — Idle / session timers

Derive in the state layer from event timestamps (no core change):
"time since last shot", idle-elapsed, last-session durations. Add the derived
values to `UiSnapshot`.

### READ-side acceptance

- New events have core decode tests; `cargo test` / `cargo build` clean.
- typeshare + wasm regenerated; new `UiSnapshot` fields typed.
- `pnpm check` → 0/0; `pnpm build` clean.
- **No component changes** — verify the new fields have no UI readers yet.
- Work on a branch; commit logical checkpoints.

## UI side — DEFERRED (do not implement)

Listed so the read side is shaped to serve it. Each becomes its own task later.

- **Machine diagnostics screen** — MMR registers, serial / model / firmware,
  calibration, USB-charger, fan, accelerometer (Settings → Machine, expanded).
- **Shot comparison / overlay** — DSx-style multi-slot graph cache, two-shot
  side-by-side compare, reference-shot ("godshot") overlay, predicted-profile
  overlay.
- **Idle / ready indicators** — time-since-last-shot, heating-progress %,
  warmup / ready state, sleep & steam / flush countdowns.
- **Error banner** — surface `machineError` as a user-facing alert.
- **Resistance** — a chart series + optional readout.
- **Maintenance / diagnostics actions** — descale / clean / flush workflows,
  calibration screens, espresso lifetime counter.
- **3rd-party / cloud** — Visualizer upload/download, profile-repository sync.
- **DSx conveniences** — derived on-scale bean-weight / milk-weight, extraction
  ratio surfaced live.

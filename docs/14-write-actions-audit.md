# 14 — Write-actions audit: legacy de1app + DSx2 → Crema

**Status:** planning only — no core changes in this branch
**Branch:** `write-actions-audit` (off `main`, so the rename branch is untouched)
**Companion:** `docs/02-ble-protocol.md` (wire format), `docs/04-mvp-scope.md`
(what is in / out), `docs/08-ffi-and-web-scope.md` (FFI shape),
`docs/13-deferred-refactors.md` (the scale-neutral seam).

---

## Introduction

Crema's read paths (`docs/10-wiring-existing-read-paths.md`,
`docs/11-new-read-paths-and-ui.md`) have landed. The write paths have lagged:
today the FFI can ask the DE1 to change machine state, write `ShotSettings`,
read MMR / calibration, write water-level settings, and drive scale-side
configuration — but **profile upload, MMR writes, calibration writes, and
firmware update are not yet wired through.** This doc enumerates every write
the legacy Tcl app (de1app) and the DSx2 skin expose, contrasts each with
Crema's current bridge surface, and proposes the Rust API + FFI extension
needed to close the gap.

**Scope.** Writes the user can trigger (directly via a button, or reactively
through the legacy event loop) and that hit either the DE1 over BLE or a
connected scale. Disk writes (profile JSON files, settings TOML, shot history),
HTTP uploads (Visualizer), and tablet-side niceties (screen-saver toggles)
are listed under Out-of-scope with explicit deferral reasons.

**Sources.**

- Legacy de1app (Tcl, official) — `/Users/adrianmaceiras/code/de1app/de1plus/`,
  read-only reference, never modified.
- DSx2 — `/tmp/dsx2-audit-writes/` (shallow clone of
  `https://github.com/Damian-AU/DSx2`, ~5000 LOC: `code/procs_vars.tcl`,
  `code/save_and_load.tcl`, `code/slow_scale_filtering/plugin.tcl`).
- Crema — `/Users/adrianmaceiras/code/crema/`, with the current write surface in
  `core/de1-protocol/src/{command,profile,mmr,calibration,firmware}.rs`,
  `core/de1-app/src/event.rs` (`enum Command`, `enum WriteTarget`),
  `core/de1-wasm/src/lib.rs`, `core/de1-ffi/src/lib.rs`,
  `web/src/lib/core/index.ts`.

---

## Methodology

1. **Legacy:** grep each Tcl source file for the only four ways a write
   reaches the DE1 — `de1_comm write …` (the BLE-write dispatcher in
   `de1_comms.tcl:182`), `mmr_write` (a wrapper around `de1_comm write
   WriteToMMR`), `userdata_append … ble write …` (direct scale writes from
   `bluetooth.tcl`), and the firmware-update helpers (`firmware_upload_next`,
   `de1_erase_firmware`). Cross-reference the GUI entry points (the `start_*`
   family in `machine.tcl`, the `proc set_*` settings setters in
   `de1_comms.tcl`).
2. **DSx2:** grep `procs_vars.tcl` for the same call set. DSx2 is a *skin*
   plugin — it draws its own pages but reuses every write path from de1app, so
   it adds **no new write actions**, only different UI affordances on the same
   writes. The only DSx2-specific reactive write is `de1_send_steam_hotwater_settings`
   triggered when the user adjusts the on-screen steam/water temperature
   dials (`procs_vars.tcl:1505,1522,1601,2075-2090,4602`) and the same
   `skip to next` write on the espresso-frame "Next" button
   (`procs_vars.tcl:1830`). DSx2's own `slow_scale_filtering` plugin is
   read-only post-processing — no writes.
3. **Crema:** read the `CremaBridge` impl in `de1-wasm/src/lib.rs` and the
   FFI bridge in `de1-ffi/src/lib.rs` for the existing methods, then compare
   field-by-field against the legacy write set.
4. **Wire form** cross-references the protocol crate. Most legacy procs have a
   direct counterpart in `core/de1-protocol/src/` already — the codec was
   written before the bridge.

---

## Idiomatic-Rust conventions for the implementer

These were established on the `rust-units-idiomatic` branch (see commits
`30243aa`, `be38ae6`, `2e74556`); they govern every new API proposed below.

- **Time:** `std::time::Duration` in domain / app code. The protocol layer
  keeps `f32` and unit-suffixed field names *only* on the wire-encode struct
  (e.g. `ShotSettings::steam_timeout_s`) — the suffix documents the wire
  unit. Cross the seam *at the boundary*: the bridge method takes a
  `Duration`, encodes it to seconds, then hands the seconds to the codec.
- **Naming:** snake_case fields; drop unit suffixes on internal/FFI fields;
  put the unit in the doc-comment instead. Example: not `weight_g: f32` on
  the `Event::ScaleReading` payload but `weight: f32` with the doc saying
  "grams". The current event payload still uses suffixes (`weight_g`,
  `flow_g_per_s`) — this is the rename in flight on `rust-units-idiomatic`;
  new write APIs should land in the renamed shape.
- **Enums:** `#[non_exhaustive]` on every enum that may grow
  (`WriteTarget`, `Command`, the future `WriteAction`). `#[typeshare]` on
  anything that crosses to TS.
- **Numbers:** use the rich types from the codec (`MachineState`,
  `MmrRegister`, `CalTarget`) rather than `u8`/`u32` once a value is
  modelled — the wasm bridge already mirrors them.
- **Errors:** internal write paths return `Result<CoreOutput, AppError>` and
  fold validation failures into the existing `AppError` variants; the bridge
  layer surfaces them as a `DecodeError`-equivalent event so the shell can
  display the message without unwrapping.

---

## Categories

The audit groups writes by what they change inside the machine. Within each
table, columns are:

- **Action** — the legacy proc name (clickable in the source).
- **Trigger** — the button / screen / event that fires it.
- **Wire** — BLE characteristic + the protocol-crate type or proc.
- **Crema today** — Supported / Partial / Missing, with the bridge method (if
  any) and the `Command`/`WriteTarget` variant.
- **Proposed Rust API** — function signature + crate it belongs in. The
  *internal* call uses `Duration`/typed enums; the wire struct retains units.
- **FFI surface** — `Command`/`WriteTarget` extension and the matching
  `CremaBridge` method (mirrored verbatim in `de1-ffi`).
- **Effort** — S / M / L.
- **Risk / Priority** — H / M / L.

### 1. Machine-state control (`RequestedState`, `cuuid_02`)

| # | Action | Trigger | Wire | Crema today | Proposed Rust API | FFI surface | Effort | Risk/Pri |
|---|--------|---------|------|-------------|-------------------|-------------|--------|----------|
| 1.1 | `start_idle` | "Stop" button on every page (`machine.tcl:1015`); reactive on connect | `RequestedState=Idle` (0x02) via `de1_send_state` | **Supported** — `request_machine_state(MachineRequest::Idle)` → `Command::WriteCharacteristic{target: De1RequestedState, …}` | reuse | reuse | — | H |
| 1.2 | `start_sleep` | "Sleep" icon / screen-saver timeout (`machine.tcl:1113`) | `RequestedState=Sleep` (0x00) | **Supported** — `MachineRequest::Sleep` | reuse | reuse | — | H |
| 1.3 | `start_espresso` | "Start" button on the brew page (`machine.tcl:916`) | `RequestedState=Espresso` (0x04) | **Supported** — `MachineRequest::Espresso` | reuse | reuse | — | H |
| 1.4 | `start_steam` | Steam tap button (`machine.tcl:814`) | `RequestedState=Steam` (0x05) | **Supported** — `MachineRequest::Steam` | reuse | reuse | — | H |
| 1.5 | `start_water` | Hot-water tap button (`machine.tcl:983`) | `RequestedState=HotWater` (0x03) | **Supported** — `MachineRequest::HotWater` | reuse | reuse | — | M |
| 1.6 | `start_flush` | "Flush" button (`machine.tcl:748`) | `RequestedState=HotWaterRinse` (0x06) | **Supported** — `MachineRequest::Flush` | reuse | reuse | — | M |
| 1.7 | `start_cleaning` | Cleaning cycle button (`machine.tcl:723`) | `RequestedState=Clean` (0x09) | **Supported** — `MachineRequest::Clean` | reuse | reuse | — | L |
| 1.8 | `start_descale` (not yet a proc; `de1_state(Descale)` from machine.tcl) | Descale-cycle button | `RequestedState=Descale` (0x07) | **Supported** — `MachineRequest::Descale` | reuse | reuse | — | L |
| 1.9 | `move_to_the_next_step` / `start_next_step` | Espresso "Next frame" button (`machine.tcl:954`, DSx2 `procs_vars.tcl:1829`) | `RequestedState=SkipToNext` (0x0A) | **Supported** — `MachineRequest::SkipToNext` | reuse | reuse | — | H |
| 1.10 | `start_schedIdle` | Scheduler wake (`machine.tcl:1074`); fw ≥ 1293 uses `SchedIdle` (0x0D), older firmware uses `Idle` | **Missing** — no `MachineRequest::SchedIdle` variant; current path collapses to Idle | add `MachineState::SchedIdle` variant if not present; expose `MachineRequest::SchedIdle` | non-exhaustive: append `MachineRequest::SchedIdle` | S | L |
| 1.11 | `start_steam_rinse` | Steam-rinse tap (`machine.tcl:774`) | `RequestedState=SteamRinse` (0x0B) | **Missing** — no `MachineRequest::SteamRinse` | append `MachineRequest::SteamRinse` mapping to `MachineState::SteamRinse` (already defined in `de1-protocol`) | append variant; same `request_machine_state` method | S | L |
| 1.12 | "Air-purge" (rare, used by some plugins) | not exposed in stock UI | `RequestedState=AirPurge` (0x0C) | **Missing** | append `MachineRequest::AirPurge` | append variant | S | L |

**Conclusion.** Machine-state control is essentially complete — three rarely-used
state variants (SchedIdle, SteamRinse, AirPurge) are the only gaps. Total wire
cost: adding three lines to the existing match in `wasm` + `ffi`.

### 2. Profile management (`HeaderWrite` + `FrameWrite`, `cuuid_0F` / `cuuid_10`)

| # | Action | Trigger | Wire | Crema today | Proposed Rust API | FFI surface | Effort | Risk/Pri |
|---|--------|---------|------|-------------|-------------------|-------------|--------|----------|
| 2.1 | `de1_send_shot_frames` | Profile selected (`vars.tcl:select_profile` ⇒ `send_de1_settings_soon` ⇒ `save_settings_to_de1` ⇒ `de1_send_shot_frames`); also pre-flight before `start_espresso` for GHC machines | `HeaderWrite` (1× 5-byte) + N× `FrameWrite` (8-byte each) + `ShotTail` + optional `ExtensionFrame`s | **Missing at FFI** — codec exists (`profile::{ShotHeader, ShotFrame, ExtensionFrame, ShotTail}`); no `assemble`, no `Command` variant, no bridge method | `de1_protocol::profile::assemble(profile: &Profile) -> Vec<ProfileFrame>` returning a `Vec<[u8; N]>` with the right slice tags; `de1_app::CremaCore::upload_profile(&self, profile: &Profile) -> CoreOutput` enqueues one `WriteCharacteristic{target: De1Header, data: header.encode()}` then one `WriteCharacteristic{target: De1Frame, data: frame.encode()}` per frame in order. Profile already typed in `de1-domain::profile`. | extend `WriteTarget` with `De1Header`, `De1Frame`; `CremaBridge::upload_profile(&mut self, profile_json: &str) -> String` (parses to `Profile`, calls core); legacy "Confirm frames acked" check folds into the existing `MachineStateChanged` listener — the DE1 echoes the frame on `cuuid_10` notify, which we don't yet subscribe to → also add `Source::De1FrameAck` + a small "frames-sent" tracker in the core that emits a `ProfileUploaded` event when all frames ACK | L | H |
| 2.2 | `set_tank_temperature_threshold` (companion to 2.1, only when profile is `settings_2c`) | reactive — `de1_send_shot_frames` writes it as part of the upload (`de1_comms.tcl:1478`) | MMR `0x80380C` (`TankTempThreshold`) | **Partial** — register modelled in `MmrRegister::TankTempThreshold`; no MMR-write proto (see §4) | folded into the profile-upload path: `upload_profile` enqueues the MMR write last (or `0` for non-advanced profiles). Needs the generic MMR-write API in §4. | piggybacks on §4 | (covered in §4) | piggybacks |

**Notes.**

- The legacy app retries on a 500 ms timer when not all frames ACK
  (`confirm_de1_send_shot_frames_worked`, `de1_comms.tcl:1488`). Crema's
  core is sans-IO so it cannot retry on a timer; surface a `ProfileUploadFailed{reason}`
  event after `n` frames un-acked-within-window and let the shell decide. The
  watchdog timer ("frames not all ACKed after T") lives in the shell, the
  *decision* (which frames went out, which came back) lives in the core.
- Extension frames: codec already handles them; `assemble` should emit them
  *between* normal frames and the tail, indexed by the frame they extend.
- The Crema `Profile` type (`de1-domain::profile`) is already richer than the
  Tcl encoder needs; `assemble` will be the translation function.

### 3. Steam / hot-water / flush settings (`ShotSettings`, `cuuid_0B`)

| # | Action | Trigger | Wire | Crema today | Proposed Rust API | FFI surface | Effort | Risk/Pri |
|---|--------|---------|------|-------------|-------------------|-------------|--------|----------|
| 3.1 | `de1_send_steam_hotwater_settings` | Settings page steam-temp / steam-timeout / hot-water-temp / hot-water-volume / hot-water-timeout / espresso-volume / group-temp dials (DSx2: `procs_vars.tcl:1505,1522,…`). Also reactively after `save_settings_to_de1` | 9-byte `ShotSettings` write | **Supported** — `set_steam_hotwater_settings(settings: ShotSettings)`; bridge takes 8 scalars and builds the struct (this awkward signature is forced because `wasm-bindgen` cannot pass complex structs) | keep, but **rename FFI args** per the unit-suffix convention: take a struct on the Rust side, scalars on the wasm side documented as "seconds" / "°C" / "mL" rather than `_s` / `_c` / `_ml` suffixes. Internal use of `Duration` for the two timeouts inside the new `app::SteamHotWaterConfig` wrapper that drops to `ShotSettings` at the encode seam. | rename signature once; behaviour unchanged | S | L |
| 3.2 | `de1_send_waterlevel_settings` | Settings → "Refill at" dial (`de1_comms.tcl:1362`) | 4-byte `WaterLevels` write (`cuuid_11`) | **Missing at FFI** — codec exists (`command::WaterLevels`); no `Command`/`WriteTarget` variant, no bridge method | `de1_app::CremaCore::set_refill_threshold(threshold_mm: f32) -> CoreOutput` → emits one `WriteCharacteristic{target: De1WaterLevels, data: WaterLevels{current_mm: 0.0, refill_threshold_mm}.encode().to_vec()}`. The legacy app writes `current_mm: 0` (it's a write of the threshold only); preserve that. | append `WriteTarget::De1WaterLevels`; `CremaBridge::set_refill_threshold(mm: f32) -> String` | S | M |
| 3.3 | Eco-steam mode | Settings "Eco-steam" switch — reactively rewrites `ShotSettings` to drop steam-target temp when machine has been idle for N minutes (`machine.tcl:in_eco_steam_mode`) | same `ShotSettings` write with lower `steam_temp_c` | **Supported** — `enable_steam_eco_mode(enabled, now_ms)` already implemented (it remembers the user's target, swaps for the lower eco target on a tick, restores on disable). Wired through both bridges. | keep | keep | — | L |

### 4. MMR register writes (`WriteToMMR`, `cuuid_06`)

The legacy app exposes one settings setter per MMR register; each goes through
`mmr_write note address length value` (`de1_comms.tcl:1025`), which fans out to
the single 20-byte `WriteToMMR` packet on `cuuid_06`. The wire write itself is
already in `de1-protocol::mmr::write_request(address, data)` — so the work is
the *FFI plumbing*, not the codec.

| # | Action | Trigger | Wire (MMR address) | Crema today | Effort | Risk/Pri |
|---|--------|---------|--------------------|-------------|--------|----------|
| 4.1 | `set_fan_temperature_threshold` (`de1_comms.tcl:1329`) | Settings → Machine → "Fan turn-on temp" | `0x803808` (`FanThreshold`), 1-byte LE value | **Missing at FFI** — register modelled, no write path | S | M |
| 4.2 | `set_tank_temperature_threshold` (`de1_comms.tcl:1060`) | Reactive after a profile upload (only for advanced profiles); preheats with `60 °C` for ~4 s, then sets the user value | `0x80380C` (`TankTempThreshold`), 1-byte LE value | **Missing at FFI** | M (the preheat dance needs a 4-s timer in the shell — surface a `MmrWrite` command and a follow-up tick-scheduled second `MmrWrite`) | M |
| 4.3 | `set_steam_flow` (`de1_comms.tcl:1210`) | Settings → Steam → "Steam flow" | `0x803828` (`SteamFlow`), 4-byte LE, value = `int(100 × rate)` | **Missing at FFI** | S | M |
| 4.4 | `set_steam_highflow_start` (`de1_comms.tcl:1286`) | Settings → Steam → "Initial high-flow seconds" | `0x80382C` (`SteamHighFlowStart`), 4-byte LE, value = `int(100 × seconds)` (sub-second precision required — legacy default 0.7s = 70) | **Missing at FFI** | S | L |
| 4.5 | `set_ghc_mode` (`de1_comms.tcl:1304`) | Settings → Machine → "Group head control" | `0x803820` (`GhcMode`), 1-byte | **Missing at FFI** | S | M |
| 4.6 | `set_hotwater_flow_rate` (`de1_comms.tcl:1169`) | Settings → Hot water → "Flow rate" | `0x80384C` (`HotWaterFlowRate`), 2-byte LE, value = `int(10 × rate)` | **Missing at FFI** | S | L |
| 4.7 | `set_flush_flow_rate` (`de1_comms.tcl:1188`) | Settings → Flush → "Flow rate" | `0x803840` (`FlushFlowRate`), 2-byte LE, value = `int(10 × rate)` | **Missing at FFI** | S | L |
| 4.8 | `set_flush_timeout` (`de1_comms.tcl:1195`) | Settings → Flush → "Timeout" | `0x803848` (`FlushTimeout`), 2-byte LE, value = `int(10 × seconds)` | **Missing at FFI** | S | L |
| 4.9 | `set_usb_charger_on` (`de1_comms.tcl:1150`) | Settings → Machine → "USB charger" toggle (Decent-only feature) | `0x803854` (`UsbChargerOn`), 1-byte (0/1) | **Missing at FFI** | S | L |
| 4.10 | `set_user_present` (`de1_comms.tcl:1163`) | Reactive — written when a user touches the screen, so the firmware doesn't sleep on inactivity | `0x803860`, 1-byte (always 1) | **Missing at FFI** — and address is not in `MmrRegister` (we model `FeatureFlags=0x803858`, not the separate `0x803860`). Worth confirming whether `UserPresent` is a distinct register or a write to `FeatureFlags`. | S; **add new MmrRegister variant** | L |
| 4.11 | `set_feature_flags` (`de1_comms.tcl:1202`) | Reactive (legacy app sets `UserNotPresent` on sleep) | `0x803858` (`FeatureFlags`), 2-byte | **Missing at FFI** | S | L |
| 4.12 | `set_refill_kit_present` (`de1_comms.tcl:1234`) | Reactive — `send_refill_kit_override` writes the user-overridden value; "0/1/2/auto" | `0x80385C` (`RefillKit`), 4-byte | **Missing at FFI** | S | L |
| 4.13 | `set_heater_voltage` (`de1_comms.tcl:1279`) | First-run setup — "120 V / 240 V" radio | `0x803834` (`HeaterVoltage`), 1-byte | **Missing at FFI** | S | M (mis-setting can damage heater — confirm before send) |
| 4.14 | `set_cupwarmer_temperature` (`de1_comms.tcl:1177`) | Settings → Cup warmer (Bengle model only — guarded by `is_bengle_model`) | `0x803874` (`CupWarmerTemp`), 2-byte | **Missing at FFI** — guard model check in the bridge | S | L |
| 4.15 | `set_calibration_flow_multiplier` (`de1_comms.tcl:1334`) | Settings → Calibration → "Flow calibration" slider; value = `int(1000 × multiplier)` | `0x80383C` (`CalibrationFlowMultiplier`), 2-byte | **Missing at FFI** | S | M |
| 4.16 | `set_heater_tweaks` — composite write that fans out to: `phase_1_flow_rate`, `phase_2_flow_rate`, `hot_water_idle_temp`, `espresso_warmup_timeout`, `steam_two_tap_stop`, `set_flush_timeout`, `set_flush_flow_rate`, `set_hotwater_flow_rate` (`de1_comms.tcl:1123`) | Reactive — called once on connect to push the user-saved values | seven MMR writes in sequence | **Missing** at FFI level; `steam_two_tap_stop` not in `MmrRegister` yet (`0x803850`); the rest are present | M | L |

**Proposed Rust API for the whole §4 row set** — one generic, one helper per register-set:

```rust
// de1-protocol — already exists in mmr.rs
pub fn write_request(address: u32, data: &[u8]) -> [u8; MMR_PACKET_LEN];

// de1-app — new
impl CremaCore {
    /// Write a single MMR register. `value` is the *raw* word that the
    /// register expects, scaled per `MmrRegister`'s documentation
    /// (e.g. `FlushTimeout` = `int(10 × seconds)`, `CalibrationFlowMultiplier`
    /// = `int(1000 × multiplier)`). The bridge or a higher-level helper
    /// applies the scale; the core does not, because some registers are
    /// `f32`-scaled and some are bitmasks.
    pub fn write_mmr(&self, register: MmrRegister, value: u32) -> CoreOutput;

    /// Per-register typed helpers for the user-facing settings, so the wasm
    /// bridge does not have to spell out "value = int(10 * seconds)" in
    /// JavaScript. Each takes a Duration / f32 / bool and converts.
    pub fn set_fan_threshold(&self, temp_c: u8) -> CoreOutput;
    pub fn set_tank_threshold(&self, temp_c: u8) -> CoreOutput;
    pub fn set_steam_flow(&self, ml_per_s: f32) -> CoreOutput;
    pub fn set_steam_highflow_start(&self, dur: Duration) -> CoreOutput;
    pub fn set_ghc_mode(&self, mode: u8) -> CoreOutput;        // 0/1/2/3
    pub fn set_hot_water_flow_rate(&self, ml_per_s: f32) -> CoreOutput;
    pub fn set_flush_flow_rate(&self, ml_per_s: f32) -> CoreOutput;
    pub fn set_flush_timeout(&self, dur: Duration) -> CoreOutput;
    pub fn set_calibration_flow_multiplier(&self, multiplier: f32) -> CoreOutput;
    pub fn set_usb_charger_on(&self, enabled: bool) -> CoreOutput;
    pub fn set_heater_voltage(&self, volts: u8) -> CoreOutput;  // 120 or 240
    pub fn set_cupwarmer_temperature(&self, temp_c: u8) -> CoreOutput;  // Bengle only
    pub fn set_refill_kit_present(&self, state: u8) -> CoreOutput; // 0/1/2
}
```

**FFI surface (`Command` extension):** none needed beyond `WriteTarget::De1MmrRequest`,
which already exists (it's used today for *reads*). Reads pass a 20-byte
`ReadFromMMR` body; writes pass a 20-byte `WriteToMMR` body to the **same**
characteristic UUID (`cuuid_06` vs `cuuid_05` — actually different UUIDs; the
read goes to `0xA005` and the write goes to `0xA006`). Confirm: today's
`WriteTarget::De1MmrRequest` maps to the read UUID `A005`. **Add a sibling
`WriteTarget::De1MmrWrite` mapping to `A006`.**

**`CremaBridge` methods:** one per setter in §4 (mirroring the per-register helpers).

**Effort estimate.** Each setter is ~10 lines of code (build the value bytes,
call `write_request`, wrap in a `CoreOutput`). Total: ~150 LOC + tests, all
purely additive. **S** for each row, **M** for the §4 block as a whole. The
risk-up rows are 4.2 (preheat dance) and 4.13 (heater voltage — user can break
their machine).

**Two new `MmrRegister` variants needed:**

- `UserPresent` at `0x803860` (legacy `set_user_present`).
- `SteamTwoTapStop` at `0x803850` (legacy `set_heater_tweaks` row).

### 5. Calibration writes (`Calibration`, `cuuid_12`)

| # | Action | Trigger | Wire | Crema today | Proposed Rust API | FFI surface | Effort | Risk/Pri |
|---|--------|---------|------|-------------|-------------------|-------------|--------|----------|
| 5.1 | `de1_send_calibration target reported measured 1` | Settings → Calibration → "Apply" after the user enters DE1-reported and externally-measured values (`de1_comms.tcl:1586`) | `Calibration` packet, `WriteKey=0xCAFEF00D`, `CalCommand=1` (Write), `CalTarget` ∈ {Flow, Pressure, Temperature} | **Missing at FFI** — codec exists (`Calibration::write`); the `Command`/`WriteTarget::De1Calibration` is already used for *reads* | `CremaCore::write_calibration(target: CalTarget, de1_reported: f32, measured: f32) -> CoreOutput` | `CremaBridge::write_calibration(target: CalSensor, de1_reported: f32, measured: f32)` | S | M |
| 5.2 | `de1_send_calibration target 0 0 2` | Settings → Calibration → "Reset to factory" for a target (`de1_comms.tcl:1586`, `calibcmd=2`) | same packet, `CalCommand=2` (ResetToFactory) | **Missing at FFI** — codec exists (`Calibration::reset_to_factory`) | `CremaCore::reset_calibration(target: CalTarget) -> CoreOutput` | `CremaBridge::reset_calibration(target: CalSensor)` | S | M |

The two writes share characteristic and `WriteTarget`. **No new variants** —
just bridge methods.

### 6. Firmware update (`FWMapRequest` + `WriteToMMR`, `cuuid_09` / `cuuid_06`)

| # | Action | Trigger | Wire | Crema today | Effort | Risk/Pri |
|---|--------|---------|------|-------------|--------|----------|
| 6.1 | `de1_erase_firmware` (legacy `de1_comms.tcl:912`) | Settings → Firmware → "Update" → confirm → first step | `FWMapRequest{fw_to_erase: 1, fw_to_map: 0, first_error: FIRST_ERROR_REQUEST}.encode()` (7 bytes) on `cuuid_09` | **Missing at FFI** — codec exists (`firmware::FWMapRequest::erase`) | L | **High-risk** |
| 6.2 | `firmware_upload_next` (`de1_comms.tcl:942`) | Step 2 of the same flow — 16-byte chunks, each `WriteToMMR` framed by `firmware_write_frame(offset, chunk)`. The legacy loop advances by 16 each frame; some firmware versions need a 1 ms inter-frame delay (shell-side concern) | many × `firmware_write_frame` to `cuuid_06` | **Missing at FFI** — codec exists; per-frame timer / progress reporting is the shell's job | L | **High-risk** |
| 6.3 | `find_first_error` (after upload finishes) | Step 3 — verify | `FWMapRequest::request_first_error()` on `cuuid_09`; DE1 replies on the same characteristic with the first-bad-byte (or `FIRST_ERROR_NONE` if verified) | **Missing at FFI** — codec exists; reply notification path also missing | included in 6.1/6.2 | piggybacks |
| 6.4 | `map_firmware` (after verified) | Step 4 — set the new slot active | `FWMapRequest::map(1)` on `cuuid_09` | **Missing at FFI** — codec exists | included | piggybacks |

**Proposed Rust API.**

```rust
// de1-app — new module: src/firmware_upload.rs (state machine + driver)
pub struct FirmwareUpload {
    image: Vec<u8>,
    bytes_uploaded: u32,
    phase: FirmwarePhase,           // Erasing → Writing → Verifying → Mapping → Done
}

impl CremaCore {
    /// Start a firmware upload. Returns the first `CoreOutput` — typically the
    /// erase write. The shell drives the rest by ticking the core and feeding
    /// the FWMapRequest notifications back through `on_notification`.
    pub fn start_firmware_upload(&mut self, image: Vec<u8>) -> CoreOutput;

    /// Cancel an in-progress upload — returns no command (the shell just stops
    /// ticking), but flips the phase to `Cancelled` so subsequent ticks return
    /// empty `CoreOutput`s.
    pub fn cancel_firmware_upload(&mut self) -> CoreOutput;

    /// Progress, for the UI. None when no upload is active.
    pub fn firmware_progress(&self) -> Option<FirmwareProgress>;
}
```

**FFI surface.**

- `WriteTarget::De1FWMapRequest` (writes the `FWMapRequest` packet).
- `Source::De1FWMapRequest` (subscribes to the reply notify).
- `Event::FirmwareProgress{phase, bytes_uploaded, bytes_total}`.
- `Event::FirmwareUpdateCompleted{verified: bool, first_error: Option<u32>}`.
- `CremaBridge::start_firmware_upload(image: Vec<u8>) -> String`,
  `cancel_firmware_upload() -> String`.
- `CremaBridge::firmware_progress() -> Option<String>` (JSON).

**Effort estimate.** **L** — three reasons. (a) The phase machine has to
coordinate writes and reply notifications across two characteristics
(`cuuid_06` and `cuuid_09`). (b) Verifying after upload uses the same
characteristic notify as the erase reply, so the core must track which reply
it's waiting for. (c) UX needs a "do you really want to do this" gate, a
progress bar, a no-power-loss warning, a "what if it fails" rescue path. The
codec is the easy part. **Highest risk in the audit** — bricking a DE1 is a
hardware-RMA-grade fault. Stay on the legacy proc's exact sequencing.

**Implementation order.** Land *last*. Everything else should be working —
including profile upload (the other multi-frame write) — before this is
attempted.

### 7. Scale commands

The Bookoo command catalogue is fully modelled in `de1-scale::bookoo` (TARE,
TIMER_START, TIMER_STOP, TIMER_RESET, plus volume / standby / flow-smoothing /
anti-mistouch / mode / auto-stop). Crema today already exposes most of it
through the bridge — listing here for completeness.

| # | Action | Trigger | Wire | Crema today |
|---|--------|---------|------|-------------|
| 7.1 | `tare_scale` (`device_scale.tcl:321`, calls per-scale `*_tare`) | Tare button on the brew page; reactive on `start_espresso`; DSx2: auto-tare on negative reading (`procs_vars.tcl:3805`) | per-scale; Bookoo: `[0x03, 0x0A, 0x01, 0, 0, xor]` to `cuuid_bookoo_cmd` (`ff12`) | **Supported** — `tare_scale()` |
| 7.2 | `bookoo_start_timer` / `_stop_timer` / `_timer_reset` (`bluetooth.tcl:487-518`) | Scale-timer Espresso-only setting drives this; reactive when machine enters/leaves Espresso | Bookoo `cmd ∈ {0x04, 0x05, 0x06}` | **Missing at FFI** — Bookoo timer commands are defined in `de1-scale::bookoo::{TIMER_START, TIMER_STOP, TIMER_RESET}` but no bridge method ties them. Crema's design favours the *device timer reading from notifications*, which renders the on-scale timer mostly cosmetic; whether to expose start/stop/reset is a product decision. **Recommend defer** unless a UI control needs it. |
| 7.3 | `set_scale_volume` (Bookoo beeper volume) | Settings → Scale (Bookoo) | `0x03 0x0A 0x07 vol 0 xor` | **Supported** |
| 7.4 | `set_scale_standby_minutes` | Settings → Scale → Auto-standby | `0x03 0x0A 0x09 min1 min2 xor` | **Supported** |
| 7.5 | `set_scale_flow_smoothing` | Settings → Scale → Flow smoothing toggle | `0x03 0x0A 0x0B on 0 xor` | **Supported** |
| 7.6 | `set_scale_anti_mistouch` | Settings → Scale → Anti-mistouch | `0x03 0x0A 0x0D on 0 xor` | **Supported** |
| 7.7 | `set_scale_mode` (Flow / Timer / Auto display) | Settings → Scale → Display mode | three-write sequence | **Supported** |
| 7.8 | `set_scale_auto_stop` (Flow-stop vs Cup-removal) | Settings → Scale → Auto-stop mode | `0x03 0x0A 0x11 mode 0 xor` | **Supported** |
| 7.9 | `query_scale_settings` (probe) | Reactive on connect, plus when the settings page opens | `0x03 0x0A 0x0F 0 0 xor` | **Supported** |
| 7.10 | `decentscale_enable_lcd` / `disable_lcd` (`bluetooth.tcl:1259`) | Reactive — `start_idle` enables LCD, `start_sleep` disables it (`machine.tcl:1055`,`1151`) | Decent-scale-specific writes | **Out of scope (deferred)** — Crema's first-class scale is Bookoo; the Decent Scale is not on the supported list. Revisit if/when Decent Scale support is added. |
| 7.11 | `decentscale_send_heartbeat` (`bluetooth.tcl:937`) | Reactive periodic heartbeat — Decent Scale only | — | **Out of scope (deferred)** — Decent Scale only. |
| 7.12 | `acaia_tare`, `felicita_tare`, etc. — per-scale tares | per-scale tare button | one write per scale | **Out of scope (deferred)** — Crema's scale matrix is Bookoo + Decent (read-only) + weight-only fallback. Other brands are not on the roadmap. |

### 8. App / shell-level writes (writes that do not touch the DE1)

Listed for completeness. None are in scope for the core / FFI.

| # | Action | What it does | Why it is out of scope |
|---|--------|--------------|------------------------|
| 8.1 | `save_settings` | Persists `::settings` array to a TOML/JSON file on disk | The native shells own preference storage (`localStorage` on web, `DataStore` on Android). The core is sans-IO. |
| 8.2 | `visualizer_upload` (plugin) | HTTP POST a finished shot to visualizer.coffee | Defer. Crema has no plugin architecture (docs/05). When shot export lands, it lives in the shell — same justification as 8.1. |
| 8.3 | `log_upload` (plugin) | HTTP POST a debug log | Same as 8.2. |
| 8.4 | `print_the_shot` (plugin) | Print a shot card | Same as 8.2. |
| 8.5 | Screen-saver image rotation, `change_screen_saver_img` (`machine.tcl:1141`) | Picks the next saver image, writes the path to `::settings` | UI shell only; no BLE. |
| 8.6 | `scale_disconnect_now` (`machine.tcl:1153`) | Closes the BLE handle to the scale | The shell owns BLE handles; the core only knows scale-was-told-to-do-X. |
| 8.7 | Profile save / hide / unhide (`vars.tcl:hide_profile`) | Writes the profile `.tcl` file back to disk | Crema profile storage is JSON in `localStorage` (web) / room (Android); the shell owns it. |
| 8.8 | History writes (`history_save`) | Records a finished shot to disk | Already supported via the shell's history pipeline (`web/src/lib/history/`). |

---

## Out-of-scope (consolidated)

Repeated for cross-reference:

1. **Disk writes** (settings, profiles, history) — shell-owned.
2. **HTTP uploads** (Visualizer, log upload, MQTT) — shell-owned; future
   `de1-shell-shared` crate if Web and Android need a shared HTTP client, but
   not the core.
3. **Per-scale writes for unsupported scales** (Acaia, Felicita, Atomheart
   Eclair, Skale, Hiroia Jimmy, Eureka Precisa, Solo Barista, Smartchef,
   Difluid, Varia Aku, Decent Scale heartbeat/LCD) — Bookoo is first-class;
   Decent is read-only fallback. Other brands are not on the supported list.
4. **Plugin-introduced writes** (D_Flow, A_Flow, SDB, DYE, hazard, web_api,
   mqtt) — no plugin architecture (docs/05).
5. **Screen-saver / wallpaper / icon-pack** writes — UI shell only.
6. **`set_user_present` reactive write** — has a UX dimension (sleep-on-idle);
   defer until the wider sleep/wake state machine is designed. The MMR
   register itself is added to `MmrRegister` so the write is *available* once
   the policy is decided, but no high-level method ships in the first cut.

---

## Implementation order recommendation

Land in this sequence; each step lets a UI surface ship complete.

1. **§1 state-control gaps** (1.10 SchedIdle, 1.11 SteamRinse, 1.12 AirPurge)
   — three lines each, completes the state surface that the brew/clean pages
   need.
2. **§3.2 `WaterLevels` write** — closes the "refill threshold" settings dial.
   Cheap; no new state machine.
3. **§5 calibration writes** — closes the calibration settings page. Codec is
   trivial; the bridge is the only new code.
4. **§4 MMR writes** (the §4 block as a whole) — closes the machine settings
   page. **Add `WriteTarget::De1MmrWrite`** as a sibling to `De1MmrRequest`;
   land all 16 register setters together (they share an idiom). **Also add
   two `MmrRegister` variants** (`UserPresent`, `SteamTwoTapStop`).
5. **§2 Profile upload** — the single biggest user-visible feature gap. Build
   `profile::assemble`, the new `WriteTarget::De1Header` / `De1Frame`, the
   matching `Source::De1FrameAck`, the `CremaCore::upload_profile`, and the
   `ProfileUploaded` / `ProfileUploadFailed` events. Wire to the profile
   selector. This *unblocks* DSx2-style brew flows on Crema. **High priority.**
6. **§7.2 Bookoo timer (optional)** — only if the scale-timer-on-Espresso
   product feature is wanted.
7. **§6 Firmware update** — last. High risk, big UX surface, the codec is
   ready but the orchestration is non-trivial.

Steps 1–3 are each S/L-effort and S/L-risk; steps 4–5 are M/H; step 7 is L/H.
A reasonable PR cadence: each numbered item above is one PR.

---

## Open questions

- **6.2 firmware-frame inter-write delay** — the legacy app delays 1 ms
  between frames on non-Android builds and *zero* delay on Android (it relies
  on BLE backpressure). Web Bluetooth's behaviour is closer to non-Android.
  Does the shell or the core enforce the delay? Recommend: the shell, with a
  per-platform constant.
- **2.1 frame-ACK semantics** — `cuuid_10` notifies back on each `FrameWrite`
  with the frame the DE1 actually applied (so the legacy app can verify).
  Should we subscribe by default (always) or only during a profile upload (to
  spare bandwidth)? Recommend: subscribe always — the cost is one tiny
  notification per frame upload, and the *handler* is cheap.
- **4.13 heater-voltage write safety** — should this require a typed-out
  confirmation ("type 240 to confirm")? Recommend: yes, in the shell, mirroring
  the legacy app's modal dialog.
- **`UserPresent` (0x803860) vs `FeatureFlags` (0x803858)** — Confirm against
  a current DE1: the legacy proc writes to `0x803860` for `set_user_present`
  *and* writes a different bit-pattern to `0x803858` (`set_feature_flags`).
  Some online discussions conflate them; the `de1_comms.tcl` source is clear
  they are distinct addresses. Treat as distinct registers until a captured
  firmware-docs MMR-map says otherwise.
- **DSx2 "wf_steam_jug_auto" auto-tare** (`procs_vars.tcl:1411` family) —
  this DSx2-specific feature triggers a tare reactively when the steam
  pour finishes and the user has the auto-tare toggle on. It is just a
  reactive call to the existing `tare_scale` write, so it does not need new
  protocol or FFI — it is a **shell-side policy** in the Web/Android brew
  page. List here so the implementer of that page does not forget the
  legacy behaviour.
- **`set_tank_temperature_threshold` preheat dance** (4.2) — the legacy proc
  writes `60 °C` for 4 s before the real value, to force a circulation. The
  4-s timer needs to live somewhere. Recommend: the core enqueues *both*
  writes (immediately, and "after 4 s") via a small scheduled-command list,
  ticked by `on_tick`. This is the same shape as the steam-eco-mode reactive
  rewrite, so the pattern already exists.
- **Profile-upload retry on partial ACK** — the legacy `confirm_de1_send_shot_frames_worked`
  retries after a half-second if not every frame was ACKed. Crema is sans-IO,
  so retry-timers live in the shell. The core emits `ProfileUploadFailed` and
  the shell decides whether to call `upload_profile` again. Decide on the
  retry budget (1 retry? 3?) before implementing.

---

## Summary

- **59 write actions** surveyed across the legacy app and DSx2 (51 in scope,
  8 explicitly out of scope).
  - §1 Machine state: **12** (9 supported, 3 missing).
  - §2 Profile management: **2** (0 supported at FFI, 2 missing).
  - §3 Steam/HW/water-level: **3** (2 supported, 1 missing — refill threshold).
  - §4 MMR register writes: **16** (0 supported at FFI, 16 missing; codec ready
    for 14 of 16; two new register addresses needed).
  - §5 Calibration: **2** (0 supported at FFI, 2 missing; codec ready).
  - §6 Firmware update: **4** (0 supported at FFI, all 4 missing; codec ready).
  - §7 Scale: **12** (7 supported, 1 deferred Bookoo timer family, 3 deferred
    Decent/Acaia/Felicita/etc., 1 query).
  - §8 App / shell: **8 — all out of scope by design.**
- DSx2 adds **zero** new write actions on top of de1app. It is a UI skin only;
  every write it triggers already exists in the legacy proc set.
- The biggest unblock-other-work item is **profile upload (§2)** — closes the
  profile-selector flow.
- The highest-risk item is **firmware update (§6)** — land last, gate behind
  an explicit "I understand" UX, mirror legacy sequencing precisely.
- The most repetitive item is **§4 MMR writes** — 16 register setters that
  share an idiom; landing them in one PR is cheaper than 16 small PRs.

---

## Cross-references I could not finish in this pass

- **`set_user_present` MMR address vs. `set_feature_flags`** — both addresses
  show up in `de1_comms.tcl` (one as `0x803860`, one as `0x803858`); I could
  not find a firmware-side documentation file in the repo confirming whether
  `0x803860` is a distinct register or aliases `0x803858` bit 1. Treated as
  distinct here; flag for confirmation during implementation.
- **`set_heater_tweaks`'s `steam_two_tap_stop`** (MMR `0x803850`) — not yet
  in `MmrRegister`. Confirmed the address from `de1_comms.tcl:1133`; will need
  adding when §4 lands. Treated as confirmed.
- **`cuuid_10` (`FrameWrite`) notify semantics** — the legacy app subscribes
  to this characteristic to read back the frame the DE1 applied
  (`de1_comms.tcl:1488` calls `confirm_de1_send_shot_frames_worked`). I did
  not verify which exact payload format the DE1 sends back (the legacy
  parses with `parse_binary_shotframe`, identical to the write format). Worth
  one BLE capture against a real DE1 before implementing §2.
- **Firmware-update timing on Web Bluetooth** — open in question (above).
  Would benefit from a Web Bluetooth firmware-upload spike.

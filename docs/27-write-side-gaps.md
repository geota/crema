# 27 — DE1 Write-Side Gaps — Consolidated

**Status:** 2026-05-23 snapshot. Read against `crema@main`,
`reaprime@HEAD` (`lib/src/models/device/impl/de1/`), `de1app@74cacdcd`
(de1plus/de1_comms.tcl @ 2026-05-22).

**Companions superseded for write-gap tracking:** `docs/14` (legacy
de1app + DSx2 audit, partial), `docs/15` (app-level writes, separate
concern), `docs/16` (profile upload, landed), `docs/17` (firmware update,
still v2), `docs/21` (write-on-configure MMR plan, scoping doc).

This doc is the single de-duped index of every user-initiated machine
state change, MMR register write, characteristic write, command-byte
sequence, or BLE-side configuration call across the three sources. It
re-conciles `docs/14`'s stale rows against what has since shipped.

## Summary

**50 distinct write capabilities** surveyed across the three sources
(de-duped from ~75 source-side procs/methods). State of play:

- `shipped`: **24** — wired end-to-end (core + wasm bridge + UI hooks).
- `core-only`: **6** — wasm/core supports it, no UI surface yet.
- `partial`: **2** — UI exists but the write is dormant or a no-op.
- `missing`: **18** — no core support, no UI; codec available for
  most (only LED strip + integrated-scale tare have no codec).

By tier:

- **Tier 1 (blocking common workflows):** 19 capabilities — 18 shipped,
  0 core-only, 0 partial, 1 missing (calibration write).
- **Tier 2 (polish / advanced):** 18 capabilities — 5 shipped,
  4 core-only, 1 partial, 8 missing.
- **Tier 3 (rare / diagnostic):** 13 capabilities — 1 shipped,
  2 core-only, 1 partial, 9 missing.

`docs/14` is materially stale: it lists most of §3.2 (refill), §4 (MMR
writes), and the §1 state-control gaps as *missing*; almost all of
those have shipped in the intervening rename + write-action passes.
Profile upload (`docs/14` §2.1, called out as the biggest unblock-other-
work item) has shipped end-to-end. The remaining hard gaps cluster
around (a) **calibration writes / reset-to-factory** — codec exists,
no bridge method at all; (b) **firmware update** — codec exists, no
state machine; (c) **per-register Tier 2 MMR setters** that pivoted to
ride only on `set_heater_tweaks` (phase 1/2 flow rate, hot water idle
temp, espresso warmup timeout, steam two-tap stop); (d) **Bengle
peripherals** (LED strip, cup-warmer outside the existing MMR setter,
integrated-scale SAW), all of which are still firmware-TBD on
reaprime's side.

Most surprising: the calibration-write surface is **completely** unwired
even at the bridge layer despite the codec being present and tested —
the only sensor-calibration UX in Crema today is the legacy `set_calibration_flow_multiplier`
MMR write (which is *not* the same as the 3-target `Calibration`
characteristic write). The full sensor calibration UI is a Tier-1-grade
gap masquerading as Tier 2 because no UI even gestures at it yet.

## By tier

### Tier 1 — blocking common workflows

| # | Capability | Status | Summary | Why it matters | Impl spec (reaprime + de1app trace) |
|---|---|---|---|---|---|
| 1 | Set machine state: Sleep | shipped | `RequestedState=0x00` write to `cuuid_02`. | Tier 1 — sleep / wake is the main idle gesture. | `core/de1-app/src/lib.rs:518 request_machine_state` → `WriteTarget::De1RequestedState`. de1app `de1_comms.tcl:1375 de1_send_state`; reaprime `unified_de1.dart:229 requestState`. |
| 2 | Set machine state: Idle | shipped | `RequestedState=0x02`. Doubles as shot-stop and wake-from-sleep. | Tier 1. | Same path as #1. de1app `machine.tcl:1015 start_idle`. |
| 3 | Set machine state: Espresso | shipped | `RequestedState=0x04`. Begins shot using last-uploaded profile. | Tier 1 — the primary user action. | Same path. de1app `machine.tcl:916 start_espresso`. |
| 4 | Set machine state: Steam | shipped | `RequestedState=0x05`. | Tier 1. | de1app `machine.tcl:814 start_steam`. |
| 5 | Set machine state: HotWater | shipped | `RequestedState=0x06` (legacy proc `start_water`; firmware enum still calls it HotWater). | Tier 1 — required for non-espresso pours; volume / weight stop wires through ShotSettings. | de1app `machine.tcl:983 start_water`. |
| 6 | Set machine state: Flush / HotWaterRinse | shipped | `RequestedState=0x0F` (firmware enum: `hotWaterRinse`). | Tier 1 — pre-shot and post-shot cleanliness gesture. | de1app `machine.tcl:748 start_flush`. |
| 7 | Set machine state: SkipToNext | shipped | `RequestedState=0x0E`. Advances espresso frame. | Tier 1 — "Next frame" button is core of every UI skin. | de1app `machine.tcl:954`, DSx2 `procs_vars.tcl:1829`. |
| 8 | Set machine state: Clean | shipped | `RequestedState=0x12`. | Tier 1 — cleaning cycle is part of maintenance UX. | de1app `machine.tcl:723 start_cleaning`. |
| 9 | Set machine state: Descale | shipped | `RequestedState=0x0A`. | Tier 1 — required for descale UX. | de1app `machine.tcl` (`de1_state(Descale)`). |
| 10 | Set machine state: SteamRinse | shipped | `RequestedState=0x10`. | Tier 1 — distinct steam-purge gesture from steam itself. Audit (`docs/14` §1.11) flagged missing; landed in `crema/core/de1-wasm/src/lib.rs:206 MachineRequest::SteamRinse`. | de1app `machine.tcl:774 start_steam_rinse`. |
| 11 | Set machine state: AirPurge | shipped | `RequestedState=0x14`. | Tier 1 — group dry-out after cleaning. Audit (`docs/14` §1.12) flagged missing; landed. | reaprime `de1.models.dart:74 airPurge(0x14)`. |
| 12 | Set machine state: SchedIdle | shipped | `RequestedState=0x15` (firmware ≥1293). | Tier 1 — keep-warm wake for scheduled brews. Audit (`docs/14` §1.10) flagged missing; landed. | de1app `machine.tcl:1074 start_schedIdle`. |
| 13 | Upload profile (header + frames + extensions + tail) | shipped | `ShotHeader` to `cuuid_0F`, then N × `ShotFrame` + 32+ext + tail to `cuuid_10`, with `Source::De1FrameAck` per-frame ack tracking and `ProfileUploadFailed{AckTimeout}` watchdog at 5 s. | Tier 1 — without it no user-authored brew can run. Audit (`docs/14` §2.1, the biggest unblock-other-work item) has fully landed; see `core/de1-app/src/lib.rs:1594 upload_profile`, `cancel_profile_upload:1659`, `handle_profile_frame_ack:1720`; bridge `core/de1-wasm/src/lib.rs:896 upload_profile`. | de1app `de1_comms.tcl:1439 de1_send_shot_frames`; reaprime `unified_de1.dart:295 setProfile`. |
| 14 | Write `ShotSettings` (steam/hot-water/group temp/timeout/volume) | shipped | 9-byte `ShotSettings` to `cuuid_0B`. Stored settings retained for eco-mode rewrite; hot-water volume override applied at encode time when a scale is connected (legacy `binary.tcl` weight-stop hack). | Tier 1 — every shot uses the resulting group temp and timeout; hot water and steam UX flows depend on it. | `core/de1-app/src/lib.rs:592 set_steam_hotwater_settings`; bridge `de1-wasm:704`. de1app `de1_comms.tcl:1555 de1_send_steam_hotwater_settings`; reaprime `unified_de1.dart:407 updateShotSettings`. |
| 15 | Eco-steam mode toggle | shipped | Reactive rewrite of `ShotSettings` swapping in `steam_eco_temperature_c=136°C` after idle delay; restored on disable. Driven by `SteamMonitor`. | Tier 1 — common comfort feature in DSx2 and legacy. | `core/de1-app/src/lib.rs:613 enable_steam_eco_mode`. de1app `machine.tcl:in_eco_steam_mode`. |
| 16 | Set water tank refill threshold (`WaterLevels` write) | shipped | 4-byte `WaterLevels` write to `cuuid_11`, `current_mm=0` + `refill_threshold_mm=...`. | Tier 1 — directly drives the low-water warning on the brew page. Audit (`docs/14` §3.2) flagged missing; landed. | `core/de1-app/src/lib.rs:897 set_refill_threshold`; bridge `de1-wasm:737`. de1app `de1_comms.tcl:1362 de1_send_waterlevel_settings`; reaprime `unified_de1.dart:350 setRefillLevel`. |
| 17 | Set group-head-control mode (GHC) | shipped | MMR `0x803820` (`GhcMode`), 1-byte, modes 0–3. Audit (`docs/14` §4.5) flagged missing; landed. | Tier 1 — required for GHC machines to honour app-driven brews vs. front-button-only. | `core/de1-app/src/lib.rs:698 set_ghc_mode`; bridge `de1-wasm:776`. de1app `de1_comms.tcl:1304 set_ghc_mode`. |
| 18 | Tare scale | shipped | Per-scale tare bytes via `Command::WriteScale`. Bookoo wire `[0x03, 0x0A, 0x01, 0, 0, xor]` to `cuuid_bookoo_cmd` (`ff12`). | Tier 1 — every weight-driven brew tares before pour. | `core/de1-app/src/lib.rs:938 tare_scale`; bridge `de1-wasm:558`. de1app `bluetooth.tcl:454 bookoo_tare`. |
| 19 | Write sensor calibration (flow / pressure / temperature) — Apply | missing | 14-byte `Calibration` packet to `cuuid_12` with `WriteKey=0xCAFEF00D`, `CalCommand=1 (Write)`, `CalTarget ∈ {Flow=0, Pressure=1, Temperature=2}`, `DE1Reported` + `Measured` as S32P16. Codec lives in `core/de1-protocol/src/calibration.rs:143 Calibration::write` and is fully tested; **no bridge method or core wrapper exists**. Audit (`docs/14` §5.1) flagged missing — still missing. | Tier 1 — sensors drift, every machine ships with the wrong flow factor at least; without a calibration write the UI gates entire SAW/SAV accuracy. The reason it sits in Tier 1 even though the audit calls it §5 is that the only existing calibration knob in Crema is the flow *multiplier* MMR (one number) — not the same as the 3-axis `Calibration` characteristic write. | de1app `de1_comms.tcl:1586 de1_send_calibration target reported measured 1`. Codec ready: `crema/core/de1-protocol/src/calibration.rs:143`. Use `CremaCore::write_calibration(target, reported, measured)` → one `Command::WriteCharacteristic{target: WriteTarget::De1Calibration, data: Calibration::write(target, reported, measured).encode().to_vec()}`. **No firmware lockout guard needed beyond the existing `refuse_if_firmware_locked` pattern.** |

### Tier 2 — polish / advanced

| # | Capability | Status | Summary | Why it matters | Impl spec (reaprime + de1app trace) |
|---|---|---|---|---|---|
| 20 | Set steam flow rate | shipped | MMR `0x803828` (`SteamFlow`), 4-byte LE, value = `int(100 × ml/s)`. Pre-2026-05-22 builds wrote `×10` into 1 byte (wrong); fixed in commit referenced at `core/de1-app/src/lib.rs:672` (docs/22 §2.1). | Tier 2 — power-user steam control; the milk-stretching UX. | `set_steam_flow`; bridge `de1-wasm:763`. de1app `de1_comms.tcl:1210 set_steam_flow`; reaprime `targetSteamFlow` at `0x803828`. |
| 21 | Set steam high-flow start (initial high-flow seconds) | shipped | MMR `0x80382C`, 4-byte, value = `int(100 × seconds)`. Pre-2026-05-22 builds truncated `Duration::as_secs()` (0.7s → 0); fixed in `core/de1-app/src/lib.rs:688` (docs/22 §2.2). | Tier 2 — common DSx2 setting. | `set_steam_highflow_start`; bridge `de1-wasm:771`. de1app `de1_comms.tcl:1286`. |
| 22 | Set fan-on temperature threshold | shipped | MMR `0x803808` (`FanThreshold`), 1-byte. Audit (`docs/14` §4.1) flagged missing; landed. | Tier 2 — quieter ambient operation. | `core/de1-app/src/lib.rs:647 set_fan_threshold`. de1app `de1_comms.tcl:1329`. |
| 23 | Set tank temperature threshold (immediate value) | shipped | MMR `0x80380C` (`TankTempThreshold`), 1-byte. Audit (`docs/14` §4.2) flagged missing; landed for the immediate value only — the legacy "preheat with 60 °C for 4 s" dance is shell-side and **still missing in any shell**. | Tier 2 — partial. Shell-side preheat dance is open. | `core/de1-app/src/lib.rs:658 set_tank_threshold`. de1app `de1_comms.tcl:1060` (the proc that does the preheat dance). |
| 24 | Set hot-water flow rate | shipped | MMR `0x80384C` (`HotWaterFlowRate`), 2-byte LE, value = `int(10 × ml/s)`. Audit (`docs/14` §4.6) flagged missing; landed. | Tier 2 — Americano / tea consistency. | `core/de1-app/src/lib.rs:708 set_hot_water_flow_rate`. de1app `de1_comms.tcl:1169`. |
| 25 | Set flush flow rate | shipped | MMR `0x803840` (`FlushFlowRate`), 2-byte LE, value = `int(10 × ml/s)`. Audit (`docs/14` §4.7) flagged missing; landed. | Tier 2 — group-clean gentleness. | `core/de1-app/src/lib.rs:719 set_flush_flow_rate`. de1app `de1_comms.tcl:1188`. |
| 26 | Set flush timeout | shipped | MMR `0x803848` (`FlushTimeout`), 2-byte LE, value = `int(10 × seconds)`. Audit (`docs/14` §4.8) flagged missing; landed. | Tier 2 — flush-cycle envelope. | `core/de1-app/src/lib.rs:730 set_flush_timeout`. de1app `de1_comms.tcl:1195`. |
| 27 | Set USB charger on/off | shipped | MMR `0x803854` (`UsbChargerOn`), 1-byte (0/1). Audit (`docs/14` §4.9) flagged missing; landed. | Tier 2 — useful, niche to Decent tablet. | `core/de1-app/src/lib.rs:741 set_usb_charger_on`. de1app `de1_comms.tcl:1150`. |
| 28 | Set heater feature flags (e.g. `UserNotPresent`) | shipped | MMR `0x803858` (`FeatureFlags`), 2-byte. Audit (`docs/14` §4.11) flagged missing; landed. Written reactively at connect time as `setFeatureFlags(1)` (`web/src/lib/state/app.svelte.ts:300`). | Tier 2 — enables firmware-side user-presence behaviour. | `core/de1-app/src/lib.rs:768 set_feature_flags`. de1app `de1_comms.tcl:1202`. reaprime `unified_de1.dart:340 enableUserPresenceFeature` writes `appFeatureFlags=1`. |
| 29 | Set user-present (heartbeat-style write on touch) | shipped | MMR `0x803860` (`UserPresent`), 1-byte. Distinct register from `FeatureFlags` (`docs/14` audit confirmed both addresses are independent registers). Audit (`docs/14` §4.10) flagged missing; landed. Bridged but only wired to UI via `app.svelte.ts:1096 markUserPresent` (currently called only on connect, not on every user touch — minor UX gap). | Tier 2 — partial UI wiring. The proc is reactive in legacy (called on screen tap, screen-saver dismiss). | `core/de1-app/src/lib.rs:755 set_user_present`. de1app `de1_comms.tcl:1163 set_user_present`. |
| 30 | Set calibration flow multiplier (single-knob flow adjust) | shipped | MMR `0x80383C`, 2-byte LE, value = `int(1000 × multiplier)` (legal range 0.13–2.0 → wire 130–2000). Audit (`docs/14` §4.15) flagged missing; landed. **Not to be confused with sensor calibration (#19).** | Tier 2 — coarse flow trim only; the 3-axis `Calibration` write (#19) is the proper sensor calibration path. | `core/de1-app/src/lib.rs:813 set_calibration_flow_multiplier`. de1app `de1_comms.tcl:1334`. reaprime `calFlowEst` at `0x0080383C` with `min: 130, max: 2000`. |
| 31 | Apply `set_heater_tweaks` composite write | shipped | 8 sequential MMR writes: `Phase1FlowRate`, `Phase2FlowRate`, `HotWaterIdleTemp`, `EspressoWarmupTimeout`, `SteamTwoTapStop`, `FlushTimeout`, `FlushFlowRate`, `HotWaterFlowRate`. | Tier 2 — pushed once at connect time per legacy convention; carries user-saved tunings. | `core/de1-app/src/lib.rs:838 set_heater_tweaks`. de1app `de1_comms.tcl:1123 set_heater_tweaks`. |
| 32 | Set tank threshold preheat dance | missing | The legacy proc (`set_tank_temperature_threshold`) writes `60 °C` for 4 s before the real value to force a circulation, then writes the user value. The single-shot value write (#23) is shipped; **the dance has no Crema home** — needs shell-side scheduling on a 4 s timer or core-side `on_tick`-driven follow-up write. | Tier 2 — only matters when the user actively changes tank threshold from cold. | de1app `de1_comms.tcl:1060`. Pattern available: same shape as the existing steam-eco rewrite that `SteamMonitor.on_tick` does. Suggested implementation: `CremaCore::set_tank_threshold_with_preheat(temp_c)` that returns the `60°C` write now and schedules the real value in `on_tick`. |
| 33 | Set refill kit presence override | shipped | MMR `0x80385C` (`RefillKit`), 4-byte (0=off/1=on/2=auto). Audit (`docs/14` §4.12) flagged missing; landed. | Tier 2 — rare unless the kit is misdetected. | `core/de1-app/src/lib.rs:777 set_refill_kit_present`. de1app `de1_comms.tcl:1234 send_refill_kit_override` → `set_refill_kit_present`. |
| 34 | Bookoo timer: start | partial | `core/de1-app/src/lib.rs:961 start_timer` → `Command::WriteScale{data}` with `bookoo::TIMER_START` (cmd 0x04). Bridge `de1-wasm:565`. Bookoo capability-gated. **Reactive auto-policy** also wires it: `core/de1-app/src/lib.rs:1900 map_shot_event` auto-runs reset+start on `ShotEvent::Started` and stop on `Completed` (#36). **No standalone UI button** — the audit (`docs/14` §7.2) recommended deferring the manual-trigger UI. The capability is plumbed; only the explicit button is missing. | Tier 2 — partial UI. Auto-wiring is enough for 95 % of users; manual trigger would surface for milk-only sessions where shot-state-driven auto-policy doesn't apply. | de1app `bluetooth.tcl:487 bookoo_start_timer`. |
| 35 | Bookoo timer: stop | partial | Same shape as #34. `stop_timer:974`; reactive on `ShotEvent::Completed`. | Tier 2 — same partial-UI note. | de1app `bluetooth.tcl:503 bookoo_stop_timer`. |
| 36 | Bookoo timer: reset | partial | `reset_timer:988`. Reactive on `ShotEvent::Started`. | Tier 2 — same partial-UI note. | de1app `bluetooth.tcl:471 bookoo_timer_reset`. |
| 37 | Bookoo: set beeper volume | shipped | `set_scale_volume` with `bookoo::set_volume(level)`. `level` clamped to `ScaleCapabilities::volume` range. After-write `0x0F QUERY_SETTINGS` appended. | Tier 2. | `core/de1-app/src/lib.rs:1089 set_scale_volume`. de1app per-scale `set_scale_volume`. |
| 38 | Bookoo: set auto-standby minutes | shipped | `set_scale_standby` with `bookoo::set_standby_minutes(min)`. | Tier 2. | `core/de1-app/src/lib.rs:1112`. |
| 39 | Bookoo: set flow smoothing on/off | shipped | `set_scale_flow_smoothing`. | Tier 2. | `core/de1-app/src/lib.rs:1133`. |
| 40 | Bookoo: set anti-mistouch on/off | shipped | `set_scale_anti_mistouch`. | Tier 2. | `core/de1-app/src/lib.rs:1153`. |
| 41 | Bookoo: set display mode (3-write sequence) | shipped | `set_scale_mode(mode_id)`. Three writes via `bookoo::select_mode`. | Tier 2. | `core/de1-app/src/lib.rs:1179`. |
| 42 | Bookoo: set auto-stop mode (flow-stop vs cup-removal) | shipped | `set_scale_auto_stop(mode_id)`. | Tier 2. | `core/de1-app/src/lib.rs:1204`. |
| 43 | Bookoo: query settings (`0x0F`) | shipped | `query_scale_settings`. Reactive after every config write. | Tier 2. | `core/de1-app/src/lib.rs:1070`. |
| 44 | Bookoo: query serial (`0x0A`) | shipped | Pushed once at connect-time on first weight notify (`push_connect_queries_once`). | Tier 2. | `core/de1-app/src/lib.rs:1030`. |
| 45 | Set sensor calibration: ResetToFactory | missing | Same packet as #19 with `CalCommand=2 (ResetToFactory)`, all sensor fields zeroed. Codec lives in `core/de1-protocol/src/calibration.rs:132 Calibration::reset_to_factory`; **no bridge or core wrapper**. Audit (`docs/14` §5.2) flagged missing — still missing. | Tier 2 — recovery from a bad calibration. The "Reset to factory" button next to the Apply button in legacy. | de1app `de1_comms.tcl:1586` with `calibcmd=2`. Codec ready: `crema/core/de1-protocol/src/calibration.rs:132`. Mirrors #19. |
| 46 | Set Bengle cup-warmer plate temperature | core-only | MMR `0x803874` (`CupWarmerTemp`), 2-byte. Range 0–80 °C; `0` turns the mat off. Bridged at `de1-wasm:834 set_cup_warmer_temperature`. **No UI surface** — Crema's settings panel does not yet have a cup-warmer card (the `docs/21` Phase 5 placeholder). Audit (`docs/14` §4.14) noted it but didn't track UI. | Tier 2 — Bengle-only. The reaprime REST has a dedicated `/api/v1/machine/cupWarmer` endpoint with the 0–80 °C range matching Crema's byte. | `core/de1-app/src/lib.rs:802 set_cup_warmer_temperature`. de1app `de1_comms.tcl:1177 set_cupwarmer_temperature` (guarded `is_bengle_model`). reaprime `rest_v1.yml:229 /api/v1/machine/cupWarmer`. Preconditions: should be Bengle (firmware will accept on any DE1 but it's a no-op). |
| 47 | Set per-register `Phase1FlowRate` directly | missing | MMR `0x803810`, 4-byte LE, value = `int(10 × ml/s)`. Only writable via the composite `set_heater_tweaks` (#31); no dedicated setter. | Tier 2 — power-user; reaching it via composite is fine if all 8 are batched, but a granular UI dial is what the legacy heater-tweaks settings page renders. | reaprime `heaterUp1Flow` at `0x803810`. de1app within `set_heater_tweaks`. Mirror the pattern of `set_steam_flow` for a `set_phase_1_flow_rate(ml_per_s)` setter; the `MmrRegister::Phase1FlowRate` variant + `mmr_write_command` helper are already in place. |
| 48 | Set per-register `Phase2FlowRate` directly | missing | MMR `0x803814`, 4-byte LE, value = `int(10 × ml/s)`. Composite-only today. | Tier 2 — same as #47. | reaprime `heaterUp2Flow` at `0x803814`. Mirror #47. |
| 49 | Set per-register `HotWaterIdleTemp` directly | missing | MMR `0x803818`, 4-byte LE, value = `int(10 × °C)`. Composite-only today. | Tier 2 — affects warmup time before each pour. | reaprime `waterHeaterIdleTemp` at `0x803818`. Mirror #47. |
| 50 | Set per-register `EspressoWarmupTimeout` directly | missing | MMR `0x803838`, 4-byte LE, value = `int(10 × seconds)`. Composite-only today. | Tier 2 — affects time the firmware will spend re-stabilising before declaring a shot timeout. | reaprime `heaterUp2Timeout` at `0x803838`. Mirror #47. |
| 51 | Set per-register `SteamTwoTapStop` (`steamPurgeMode`) directly | missing | MMR `0x803850`, 4-byte LE (bool 0/1). Composite-only today. | Tier 2 — UX toggle for double-tap-to-stop steam. reaprime exposes it standalone as `steamPurgeMode` in `De1SettingsRequest` (`rest_v1.yml:4044`). | reaprime `steamPurgeMode` at `0x803850`. de1app within `set_heater_tweaks`. |
| 52 | Set `FlushTemp` directly | missing | MMR `0x803844`, 4-byte LE, value = `int(10 × °C)`. **Crema-modelled** register that the legacy TCL doesn't write, but reaprime exposes both for read and write. Currently `MmrRegister::FlushTemp` exists, no setter. | Tier 2 — only reaprime exposes it; UI would need to live alongside the existing flush-flow / flush-timeout. | reaprime `flushTemp` at `0x803844`. No de1app trace. Add `CremaCore::set_flush_temp(temp_c)` mirroring `#20 set_steam_flow`'s shape. |
| 53 | Settings reset to factory baseline | missing | Reaprime exposes `DELETE /api/v1/machine/settings/reset` (`rest_v1.yml:404`): "Reapplies a baseline set of machine settings (fan threshold, heater idle temp, heater phase 1/2 flows, heater phase 2 timeout, refill kit auto mode, flow estimation multiplier, steam purge mode)." This is a *batched* set of writes the user invokes from one button. Crema has no equivalent and no individual write that mirrors all of those at once. | Tier 2 — recovery / clean-slate UX for the machine. | reaprime — defined in REST API only (`rest_v1.yml:404`). de1app has no single proc for this; legacy "reset settings" path lives in `settings_reset.tcl` (out of scope, shell concern). Suggested Crema implementation: a `CremaCore::reset_machine_defaults() -> CoreOutput` that batches the 8 standardised writes into one `CoreOutput.commands` vector. |

### Tier 3 — rare / diagnostic

| # | Capability | Status | Summary | Why it matters | Impl spec (reaprime + de1app trace) |
|---|---|---|---|---|---|
| 54 | Set machine state: ShortCal (diagnostic) | core-only | `RequestedState=0x07`. Bridged `de1-wasm:215 ShortCal`. **No UI** — should remain gated behind a service mode. | Tier 3. | reaprime `de1.models.dart:57 shortCal(0x7)`. |
| 55 | Set machine state: SelfTest (diagnostic) | core-only | `RequestedState=0x08`. Bridged `de1-wasm:217 SelfTest`. **No UI**. | Tier 3. | reaprime `de1.models.dart:58 selfTest(0x8)`. |
| 56 | Set heater voltage (120 V / 240 V hint) | shipped | MMR `0x803834` (`HeaterVoltage`), 1-byte. Audit (`docs/14` §4.13) flagged missing; landed. **Damaging if mis-set** — shell must wrap with type-to-confirm modal; no such modal in Crema today, so the bridge is essentially Tier 3-as-shipped but Tier 1-as-risk. | Tier 3 by user-frequency, Tier 1 by blast radius. The reaprime REST endpoint clamps to `[120, 230]`; Crema's byte accepts anything. **Recommended: add explicit clamp at the bridge layer and demand a confirmation token.** | `core/de1-app/src/lib.rs:791 set_heater_voltage`. de1app `de1_comms.tcl:1279`. reaprime `unified_de1.mmr.dart:663 setHeaterVoltage`. |
| 57 | Firmware update: erase slot | missing | `FWMapRequest{fw_to_erase=1, fw_to_map=0, first_error=FIRST_ERROR_REQUEST}.encode()` (7 bytes) to `cuuid_09`. Codec: `core/de1-protocol/src/firmware.rs:70 FWMapRequest::erase`. **No core orchestrator, no bridge method, no UI.** Audit (`docs/14` §6.1) flagged missing — still missing. `docs/17` is the v2 plan with the state machine; `docs/22` §2.3 corrected the erase packet shape. **High-risk** — bricking a DE1 is RMA-grade fault. | Tier 3 — rare (user runs FW update once or twice a year). | de1app `de1_comms.tcl:842 start_firmware_update` + `de1_comms.tcl:912 de1_erase_firmware`. Reaprime `unified_de1.firmware.dart:60-67 FWMapRequestData(firmwareToErase: 1, ...)`. **Preconditions:** AC power on, machine in Idle, no other writes in flight (the `firmware_locks_writes()` lockout pattern in `core/de1-app/src/lib.rs:402` is the v1 stub for this guard). |
| 58 | Firmware update: stream firmware bytes | missing | Many × `firmware_write_frame(offset, chunk)` 16-byte writes to `cuuid_06` (the MMR write characteristic; firmware payload rides the same UUID as register writes). Codec: `core/de1-protocol/src/firmware.rs:159 firmware_write_frame`. Inter-frame timing per docs/17 §7.7 is ~30 ms on Decent tablets (not 1 ms as legacy docs claim). | Tier 3 — same UX as #57. | de1app `de1_comms.tcl:942 firmware_upload_next`. reaprime `unified_de1.firmware.dart:111 batchSize/batchPause`. |
| 59 | Firmware update: request first error | missing | `FWMapRequest::request_first_error()` to `cuuid_09`. DE1 replies with first-bad-byte or `FIRST_ERROR_NONE`. Codec: `core/de1-protocol/src/firmware.rs:92`. | Tier 3. | de1app `de1_comms.tcl find_first_error`. |
| 60 | Firmware update: map active slot | missing | `FWMapRequest::map(1)` to `cuuid_09`. Codec: `core/de1-protocol/src/firmware.rs:80`. | Tier 3. | de1app `de1_comms.tcl map_firmware`. |
| 61 | Bengle LED strip: set zone colors | missing | 6 endpoint writes (frontStrip/backStrip/frontSwitch × sleeping/awake). Each color is 16-bit per RGB channel as 12-char hex (`RRRRGGGGBBBB`). **No codec exists in Crema or reaprime.** Reaprime's `led_strip_capability.dart:65` is a stub awaiting FW endpoint UUIDs. Defined in REST: `PUT /api/v1/machine/ledStrip` (`rest_v1.yml:284`). | Tier 3 — Bengle-only cosmetic; should ship once FW publishes the wire UUIDs. **Crema cannot implement this until reaprime / FW publishes the BLE characteristic IDs and color frame layout.** | reaprime `led_strip_capability.dart:13 BengleLedEndpoint` enum (all UUIDs `null`); `rest_v1.yml:284 /api/v1/machine/ledStrip`. **Blocked on Decent FW spec.** |
| 62 | Bengle LED strip: commit to NVM | missing | `POST /api/v1/machine/ledStrip/commit`. Writes one byte to `commitConfig` endpoint. | Tier 3. | reaprime `led_strip_capability.dart:79 commitLedStrip` (stub). **Blocked on FW spec.** |
| 63 | Bengle LED strip: reset cache from NVM | missing | `POST /api/v1/machine/ledStrip/reset`. Writes one byte to `resetConfig` endpoint and re-reads cache. | Tier 3. | reaprime `led_strip_capability.dart:86 resetLedStrip` (stub). **Blocked on FW spec.** |
| 64 | Bengle integrated-scale tare | missing | Write to `control` endpoint of the integrated scale. Same role as the external Bookoo tare (#18) but for the on-machine load cell. **No codec, no UUIDs.** | Tier 3 — Bengle-only. **Blocked on FW spec.** | reaprime `integrated_scale_capability.dart:152 tareIntegratedScale` (stub). |
| 65 | Bengle integrated-scale SAW target (in-FW stop-at-weight) | missing | MMR address TBD, `scaledFloat × 10` decigrams on the wire, 0.0 = SAW off. Reaprime models the slot as `BengleScaleMmr.stopAtWeightTarget` at `0x00000000` (stubbed). Reaprime exposes the capability via `MachineCapabilities` (`rest_v1.yml:3793 stopAtWeight`); the host app reflects `WorkflowContext.targetYield` to the SAW MMR. | Tier 3 — Bengle-only; subsumes Crema's existing `AutoStop::weight` for Bengle hardware. | reaprime `integrated_scale_capability.dart:172 setStopAtWeightTarget`. **Blocked on FW spec.** |
| 66 | Decent Scale: enable / disable LCD | missing | Decent-Scale-only writes. Legacy reactively enables LCD on `start_idle`, disables on `start_sleep`. **Out of Crema's roadmap** (`docs/14` §7.10) — Decent Scale is read-only fallback only. Listed for completeness. | Tier 3. | de1app `bluetooth.tcl:1259 decentscale_enable_lcd`, `:1283 decentscale_disable_lcd`. **Deferred indefinitely.** |
| 67 | Decent Scale: heartbeat | missing | Periodic write to keep the scale awake. Decent-Scale-only. **Deferred** (`docs/14` §7.11). | Tier 3. | de1app `bluetooth.tcl:937 decentscale_send_heartbeat`. |

## Methodology

Walked all five sources:

1. **`docs/14`** — read in full. Most §1.10–§1.12 (machine state),
   §3.2 (refill threshold), §4 (16 MMR setters), and §5 calibration
   rows are stale (rows landed since the doc was last updated); ~20
   audit rows have shipped. Status confirmed against the actual
   `core/de1-app/src/lib.rs` and `core/de1-wasm/src/lib.rs` impls.
2. **`docs/15`** — read in full. Entirely shell-side concerns (file
   I/O, HTTP, plugin equivalents); none of the entries are write
   capabilities in the BLE / MMR / characteristic sense. **Folded
   them out of this doc**; they remain orthogonal in `docs/15`.
3. **reaprime** — walked `lib/src/models/device/impl/de1/` end to end:
   `de1.models.dart` (`MMRItem` enum: 27 register entries), `unified_de1.dart`
   (line 249–410, ~18 set-methods + profile + shotSettings + waterLevels),
   `unified_de1.mmr.dart` (heater voltage, refill kit), `unified_de1.firmware.dart`
   (FW upload), and the two capability mixins
   (`led_strip_capability.dart`, `integrated_scale_capability.dart`).
   Cross-referenced `assets/api/rest_v1.yml` (5414 lines) for the
   REST surface — most relevant sections: `/api/v1/machine/state`,
   `/profile`, `/shotSettings`, `/waterLevels`, `/settings`,
   `/settings/advanced`, `/settings/reset`, `/calibration`, `/firmware`,
   `/cupWarmer`, `/ledStrip{,/commit,/reset}`, `/capabilities`.
4. **de1app** — walked `de1plus/de1_comms.tcl` (every `mmr_write` and
   every `de1_send_*`), `de1plus/bluetooth.tcl` (per-scale write procs,
   incl. Decent / Acaia / Felicita / Bookoo / Atomheart / Skale / Hiroia
   / Eureka / Solo Barista / Smartchef / Difluid / Varia Aku). The
   scale matrix beyond Bookoo + Decent is explicitly deferred per
   `docs/14` §7.12; not listed here.
5. **Crema** — read `core/de1-app/src/lib.rs` (1376 lines of the
   3973-line core, far enough to see every public write method),
   `core/de1-wasm/src/lib.rs` (1408 lines, the full bridge surface),
   `core/de1-protocol/src/mmr.rs`, `core/de1-protocol/src/calibration.rs`,
   `core/de1-protocol/src/firmware.rs`, and `web/src/lib/state/app.svelte.ts`
   (1230+ lines, every UI-exposed write method).

**De-dup rule:** one row per distinct firmware-side effect. The
legacy `set_heater_tweaks` proc bundles 8 MMR writes; rows #31, #47,
#48, #49, #50, #51 break those out where Crema exposes them
independently. Calibration *flow multiplier* (MMR, #30) is **separate**
from the sensor `Calibration` characteristic writes (#19, #45) —
both legacy and reaprime conflate the terminology, this doc
disambiguates.

**Status determination:** `shipped` requires (a) core method, (b)
bridge method in `de1-wasm/src/lib.rs`, and (c) UI hook in
`web/src/lib/state/app.svelte.ts`. `core-only` requires (a) + (b)
without (c). `partial` is (c) gestures at it but (a) is a no-op, or
the wiring exists but only fires reactively without manual user
control. `missing` is no (a).

## Open questions

These are places where reaprime and de1app disagree, or where
Crema's existing implementation looks subtly wrong:

1. **`appFeatureFlags` semantics.** The legacy `set_feature_flags`
   writes a 2-byte value (`UserNotPresent` flag set / cleared);
   reaprime writes a 4-byte int (`appFeatureFlags`); Crema writes
   2 bytes via `set_feature_flags(u16)`. The MMR slot at `0x803858`
   is declared 4-byte in reaprime's catalog and is a single bitmask
   — the legacy 2-byte write is **probably correct** (only the
   low 16 bits matter) but should be re-checked against an actual
   BLE capture. `docs/22` §2.4 says reaprime writes the bare `1`
   (`appFeatureFlags = 1` for user-presence enable) **at connect
   time**; Crema does the same (`web/src/lib/state/app.svelte.ts:300
   .setFeatureFlags(1)`). Open: whether the 4-byte vs 2-byte write
   differs in firmware-observed effect.

2. **`set_user_present` reactive cadence.** The legacy app writes
   it on every screen touch; Crema's `markUserPresent` only fires
   on connect. If the firmware uses absence of a recent
   `UserPresent=1` write to drive its sleep timer, Crema may
   under-write it and the DE1 may sleep mid-session. Worth a BLE
   capture to confirm.

3. **`SteamFlow` scale.** Pre-2026-05-22 builds of Crema wrote `×10`
   into 1 byte — both wrong (`docs/22` §2.1). The current code writes
   `×100` into 4 bytes. **Reaprime declares the slot 4-byte
   `scaledFloat` with `writeScale: 100.0`** — matches Crema's current
   implementation. de1app writes `int(desired_flow)` directly (no
   scale) into the same address, which gives the same wire result
   if and only if `$::settings(steam_flow)` is pre-scaled in the
   TCL setting layer; this needs confirmation against a real
   de1app settings dump. If de1app's `$::settings(steam_flow)` is
   *not* pre-scaled (i.e. holds `7.0` ml/s, not `700`), the legacy
   app has a long-standing bug that the firmware tolerates because
   the byte happens to land in a sensible range.

4. **`SteamHighFlowStart` scale.** Reaprime's `MMRItem.steamStartSecs`
   declares the field `scaledFloat` but **omits `readScale`** — their
   bug, called out in `core/de1-protocol/src/mmr.rs:213-218` doc
   comment. Crema applies `×100` per the firmware's docstring; this
   is the corrected behaviour. Worth a real-DE1 round-trip read to
   confirm.

5. **Calibration MMR `0x80383C` vs the `Calibration` characteristic.**
   The single MMR knob (#30) and the 3-axis characteristic write (#19)
   are **independent firmware concepts**; the MMR write affects only
   flow-multiplier output, the characteristic write configures the
   raw sensor mapping. The legacy app exposes **both** under different
   menus; Crema exposes only the MMR knob. Make sure the UI text
   distinguishes the two when the calibration characteristic write
   ships.

6. **Cup warmer 0.0 = off.** Reaprime's REST schema (`rest_v1.yml:3796
   CupWarmerState`) defines `0.0` as off — no separate enable flag.
   Crema's `set_cup_warmer_temperature(temp_c: u8)` takes a `u8`, so
   `0` correctly maps to "off"; the byte-level write to MMR `0x803874`
   is identical. Test on real Bengle hardware that the firmware
   actually disables the mat at `0`, not just sets the setpoint to 0
   and leaves the relay PWM running.

7. **`set_user_present(false)` semantics.** Crema can write `0` to
   `UserPresent`; the legacy app only writes `1` (the `set_user_present`
   proc always writes `1`; `0` is implicit by silence + the firmware's
   own timeout). Open: whether explicitly writing `0` differs from
   timing out — could matter for screen-saver / sleep UX.

8. **Heater voltage clamp.** Crema accepts any `u8` for `set_heater_voltage`;
   reaprime's REST API clamps to `{120, 230}` enum (`rest_v1.yml:4097`),
   and the firmware encodes "set" by adding `+1000` (so `1230` means
   "230 V committed"). Crema doesn't model the +1000 round-trip or
   the clamp at all. **Recommend hardening at the bridge layer
   before shipping a UI**; #56's "blast radius" Tier 1 note.

9. **Reaprime's `setTime` characteristic (`cuuid_03`, `A003`) is
   unused.** Neither reaprime nor de1app actually writes to it; the
   firmware presumably uses internal timekeeping. **Not worth a
   row** but worth flagging if someone reads the BLE protocol doc
   and wonders why the time-sync feature is missing.

10. **Settings reset (#53) baseline values.** Reaprime documents
    *which* 8 fields are reset (`rest_v1.yml:404 /api/v1/machine/settings/reset`)
    but **not** the values they reset to. The baseline lives somewhere
    in reaprime's server code; not in this OpenAPI spec, not in
    de1app (which has no equivalent proc). Need to read reaprime's
    server source to get the baseline numbers before implementing
    in Crema. **Resolved — see Appendix below.**

## Appendix: settings-reset baseline values

All eight baselines live in a single Dart extension method
`Defaults.applySettingsDefaults` at
`reaprime/lib/src/controllers/de1_controller.defaults.dart:39-51`.
The DELETE handler at
`reaprime/lib/src/services/webserver/de1handler.dart:263-268` does
nothing but call that method and return `202 Accepted` — no journal
entry, no `dirtyFlag`, no client-side notification, no
post-write read-back. MMR wire encoding lives in the
`MMRItem` enum at
`reaprime/lib/src/models/device/impl/de1/de1.models.dart:207-398`,
and the byte-packing happens in `_writeMMRInt` / `_writeMMRScaled`
at `unified_de1/unified_de1.mmr.dart:116-126` (Int32 little-endian,
`writeScale` applied then `.toInt()` truncates, then `clamp(min,max)`
is applied **after** scaling).

### Per-field values

| # | Field (REST name)           | User-unit value          | Wire value (Int32 LE) | MMR addr  | Scaling                       | Clamp        | Source line |
|---|-----------------------------|--------------------------|-----------------------|-----------|-------------------------------|--------------|-------------|
| 1 | `fan` (FanThreshold)        | **55 °C** (writes **50**) | `50` (0x32)           | `0x803808` | none (int32, raw °C)          | min 0, max 50 | `defaults.dart:40` |
| 2 | `heaterIdleTemp` (HotWaterIdleTemp) | **95 °C**          | `950` (0x03B6)        | `0x803818` | writeScale × 10 (deci-°C)      | none         | `defaults.dart:42` |
| 3 | `heaterPh1Flow` (Phase1FlowRate) | **2.0 mL/s**        | `20` (0x14)           | `0x803810` | writeScale × 10 (dL/s? deci-mL/s) | none      | `defaults.dart:43` |
| 4 | `heaterPh2Flow` (Phase2FlowRate) | **4.0 mL/s**        | `40` (0x28)           | `0x803814` | writeScale × 10                | none         | `defaults.dart:44` |
| 5 | `heaterPh2Timeout` (EspressoWarmupTimeout) | **4.0 s**     | `40` (0x28)           | `0x803838` | writeScale × 10 (deci-s)       | none         | `defaults.dart:45` |
| 6 | `refillKitSetting` (RefillKit) | **`auto`** (= 2)       | `2`                   | `0x80385C` | none (tri-state int32)         | none         | `defaults.dart:47`; enum at `de1_interface.dart:98-109` |
| 7 | `flowMultiplier` (CalFlowEst) | **1.0** (1.000×)         | `1000` (0x03E8)       | `0x80383C` | writeScale × 1000 (milli-x)    | min 130, max 2000 | `defaults.dart:49` |
| 8 | `steamPurgeMode` (SteamTwoTapStop) | **0** (= "always purge to drip-tray") | `0`     | `0x803850` | none (int32 mode enum)         | none         | `defaults.dart:50` |

### Important gotcha — field 1 (Fan threshold)

`MMRItem.fanThreshold` declares `min: 0, max: 50`
(`de1.models.dart:247-254`). The default literal in
`applySettingsDefaults` is **55**, which `_writeMMRInt`
**silently clamps to 50** before sending
(`unified_de1.mmr.dart:116-121`). So:

- The Dart source reads "55"
- The firmware actually receives **50**
- The REST round-trip will report **50** on subsequent
  `GET /api/v1/machine/shotSettings`

**Crema should hard-code `50` (the post-clamp value)** — that's
what the firmware ends up with, and what users see in any
read-back. Alternatively, hard-code `55` and copy the same
clamp logic; either way the wire byte is `0x32`. (This looks
like a long-standing reaprime bug or stale constant from an
era when the clamp was wider — worth a one-line PR upstream.)

### Conditionals (Bengle vs non-Bengle)

None. `applySettingsDefaults` issues the same 8 writes
regardless of model. Reaprime has a separate Bengle-only
`setRefillKitPresent(0x02)` at `unified_de1.dart:203` during
startup detection — unrelated to the reset endpoint.

### Order of writes

Strictly **sequential** with `await` on each MMR write. There is
no `Future.wait`, no batching, no delay between writes. Each
`_mmrWrite` resolves when the BLE write-with-response
acknowledges. Expected wall-clock cost: ~8 × one BLE round-trip
(roughly 200–800 ms total, dominated by connection interval).
Order is the literal source order in
`defaults.dart:40-50`: fan → heaterIdle → ph1Flow → ph2Flow →
ph2Timeout → refillKit → flowEst → steamPurge. **No
roll-back on partial failure** — if write #4 throws, writes 1–3
are already committed to firmware and writes 5–8 never happen.

### Auth, rate-limiting, side-effects

- **Auth: none.** The webserver pipeline at
  `webserver_service.dart:320-340` only adds `logRequests`
  and `corsHeaders` middleware. Any client on the LAN that
  can reach the HTTP port can issue this DELETE.
- **Rate-limiting: none.** No throttle middleware anywhere
  in `lib/src/services/`.
- **Side-effects beyond the 8 MMR writes: none.** No
  journal append, no `dirtyFlag` mutation, no broadcast to
  WS subscribers, no `_shotSettingsController.add(...)`.
  Returns `202 Accepted` with empty body the moment the 8th
  await resolves.
- **No read-back / verification.** Reaprime does not re-read
  the MMRs to confirm the writes landed. Crema can do better
  here cheaply by issuing a follow-up `getShotSettings` and
  comparing.

### Crema implementation note

The eight constants can live as a single `const` block on
`CremaCore`:

```rust
// Match reaprime/lib/src/controllers/de1_controller.defaults.dart:39
pub const RESET_FAN_THRESHOLD_C: u8 = 50;       // clamped down from reaprime's 55
pub const RESET_HEATER_IDLE_TEMP_C: f32 = 95.0;
pub const RESET_HEATER_PH1_FLOW: f32 = 2.0;
pub const RESET_HEATER_PH2_FLOW: f32 = 4.0;
pub const RESET_HEATER_PH2_TIMEOUT_S: f32 = 4.0;
pub const RESET_REFILL_KIT: RefillKit = RefillKit::Auto; // wire = 2
pub const RESET_FLOW_MULTIPLIER: f32 = 1.0;
pub const RESET_STEAM_PURGE_MODE: u8 = 0;
```

Cremaside should preserve sequential semantics (one MMR
write per setter, awaited) so partial-failure behaviour
matches reaprime, but **should** add a final read-back and
expose a `reset_machine_defaults` result that reports which
of the 8 writes succeeded — closing the hole flagged in #19
(write-path verification).

## Appendix: Decent Scale write protocol

PR E ships Decent Scale write support, overriding §7.10 / §7.11's
"deferred" decision. This appendix captures the exact wire bytes the
implementation must emit.

### Wire shape — `03 <cmd> <p0> <p1> <p2> <p3> <xor>`

Every write to the Decent Scale is a 7-byte frame: a fixed prefix
`0x03`, a 1-byte command type, four payload bytes (zero-padded), and an
XOR byte over the preceding six. Source:
`/Users/adrianmaceiras/code/de1app/de1plus/bluetooth.tcl:1195-1222`
(`decent_scale_make_command` + `decent_scale_calc_xor16`). The public
spec at decentespresso.com confirms the same shape and `0x03 ^ …`
formula. Crema's existing helper
`/Users/adrianmaceiras/code/crema/core/de1-scale/src/decent_scale.rs:36-40`
already implements this correctly.

### GATT IDs

Source: `/Users/adrianmaceiras/code/de1app/de1plus/machine.tcl:77-80`
and reaprime
`/Users/adrianmaceiras/code/reaprime/lib/src/models/device/impl/decent_scale/scale.dart:14-19`.

| Role | UUID |
| --- | --- |
| Service | `0000FFF0-0000-1000-8000-00805F9B34FB` |
| Read / notify | `0000FFF4-0000-1000-8000-00805F9B34FB` |
| Write | `000036F5-0000-1000-8000-00805F9B34FB` |
| Write-back (status echo) | `83CDC3D4-3BA2-13FC-CC5E-106C351A9352` |

All writes use **write-without-response** (legacy passes `0` as the
final arg to `userdata_append … ble write` — see `bluetooth.tcl:1280`).
Crema's existing UUID constants already match
(`decent_scale.rs:7-11`).

### LCD enable (fires on `start_idle`)

- TCL: `decentscale_enable_lcd` —
  `/Users/adrianmaceiras/code/de1app/de1plus/bluetooth.tcl:1259-1281`.
- Builder call: `decent_scale_make_command 0A 01 01 00 01` (grams) or
  `0A 01 01 01 01` (oz) — line 1274 / 1277.
- Wire (grams): **`03 0A 01 01 00 01 08`**.
- Wire (oz): **`03 0A 01 01 01 01 09`**.
- Note byte5 (= `0x01`) is the "require heartbeat" flag (legacy comment
  line 1271). The public spec's `03 0A 01 01 00 00 09` variant omits it
  and is intended for older apps that don't send heartbeats; Crema
  **does** send heartbeats, so use legacy's flagged form.
- Lifecycle caller: `start_idle` in
  `/Users/adrianmaceiras/code/de1app/de1plus/machine.tcl:1054-1056`,
  also re-fired from sleep wake at `binary.tcl:1641` and `start_schedIdle`
  at `machine.tcl:1106`. `scale_enable_lcd`
  (`bluetooth.tcl:18-27`) sends the command twice 1 s apart because the
  v1.0 firmware drops commands.
- Expected response: scale brings the OLED up and begins emitting
  weight notifications on FFF4 (type bytes `0xCE` / `0xCA`). No
  dedicated ack.

### LCD disable / power off (fires on `start_sleep`)

- TCL: `decentscale_disable_lcd` —
  `/Users/adrianmaceiras/code/de1app/de1plus/bluetooth.tcl:1283-1302`.
- Two distinct payloads, branch on
  `settings(keep_scale_on) == 1 || potentiallypoweroff != 1`:
  - **Display off, stay powered:** `decent_scale_make_command 0A 00 00`
    → wire **`03 0A 00 00 00 00 09`** (line 1288).
  - **Power off entirely (battery save):** `decent_scale_make_command 0A 02`
    → wire **`03 0A 02 00 00 00 0B`** (line 1289, v1.2+ firmware only —
    plugin `plugins/decentscale_off/plugin.tcl:17` confirms the
    fw-version requirement).
- Lifecycle caller: `start_sleep` in
  `/Users/adrianmaceiras/code/de1app/de1plus/machine.tcl:1149-1156` and
  `binary.tcl:1626`.
- Expected response: scale dims the display (or disconnects, on
  power-off); no ack.
- reaprime sends a different sleep payload at
  `decent_scale/scale.dart:273-287` (`03 0A 04 01 00 01 09` then
  `03 0A 00 01 00 01 09`) — these use cmd-byte `0x04` which legacy
  doesn't use. **For PR E, prefer legacy's `0A 00 00` — it's the form
  the published spec endorses and what existing de1app users run.**

### Heartbeat

- TCL: `decentscale_send_heartbeat` —
  `/Users/adrianmaceiras/code/de1app/de1plus/bluetooth.tcl:937-980`.
- Payload: `decent_scale_make_command 0A 03 FF FF` →
  wire **`03 0A 03 FF FF 00 0A`** (line 949).
- **Period in legacy: 1000 ms** (`after 1000 decentscale_send_heartbeat`,
  line 979).
- **Spec maximum: 5 s** (decentespresso.com — "Half Decent Scale
  disconnects if heartbeat is not received at least every 5 s").
  Reaprime uses 4 s (`scale.dart:96`); legacy uses 1 s. **Recommend
  Crema use 2 s** — well inside the 5 s window, gentler on radio than
  legacy's 1 s storm, leaves slack for jitter.
- Expected response: scale replies on FFF4 with a type-`0x0A`
  battery/info frame (reaprime parses it at `scale.dart:251-255`,
  `data[4]` = battery %). Treat the notification as a watchdog
  liveness signal.
- First heartbeat must go out **before** LCD enable on a fresh
  connection — legacy fires it on connect at
  `bluetooth.tcl:2331-2340`:
  ```
  decentscale_send_heartbeat            ; immediately
  after 2000 decentscale_send_heartbeat ; 2 s later
  after 200  decentscale_enable_lcd
  after 300  decentscale_enable_notifications
  after 400  decentscale_enable_notifications  ; double-send
  after 500  decentscale_enable_lcd            ; double-send
  ```

### Tare

- TCL: `decentscale_tare` →
  `/Users/adrianmaceiras/code/de1app/de1plus/bluetooth.tcl:1388-1413`,
  which calls `decent_scale_tare_cmd` at
  `bluetooth.tcl:1239-1244`.
- Payload: `decent_scale_make_command 0F <counter> 00 00 01` where
  `counter` rolls 253→255→0→… (`tare_counter_incr`, lines 1224-1237).
- Wire for `counter = 5`: **`03 0F 05 00 00 01 08`** (matches Crema's
  existing test at `decent_scale.rs:99-101`).
- Crema already exposes `tare(counter: u8)` at
  `decent_scale.rs:44-46` — **wire bytes are correct**. The only thing
  missing is the counter state. Reaprime hard-codes `counter = 1`
  (`scale.dart:182`) and works; legacy increments to "tell repeated
  requests apart" (`bluetooth.tcl:1226`). Crema should keep an
  incrementing counter in the capability struct.
- Tare is **independent of the timer** — no coupled `timer_start`
  write (unlike Bookoo). The only coupling in legacy is a
  button-driven UX flow in `bluetooth.tcl:2725-2734` where a long
  press on the scale button does tare + timer-reset-then-start, but
  that's button-handling, not app-driven tare.
- Legacy double-sends the tare write (`bluetooth.tcl:1411`) for v1.0
  firmware reliability — Crema should do the same.
- Expected response: weight notifications continue, with the next
  reading at zero. There is no dedicated tare-ack on FFF4 (the
  comment at `bluetooth.tcl:1394` even says "if this was a scheduled
  tare, indicate that the tare has completed" without waiting for an
  ack — legacy assumes success).

### Timer commands (already implemented, listed for completeness)

`bluetooth.tcl:1310-1386`:

| Op | Payload | Wire |
| --- | --- | --- |
| Start | `0B 03 00` | `03 0B 03 00 00 00 0B` |
| Stop  | `0B 00 00` | `03 0B 00 00 00 00 08` |
| Reset | `0B 02 00` | `03 0B 02 00 00 00 0A` |

Crema's `decent_scale.rs:49-61` already emits exactly these bytes
(verified by the test at lines 104-108). Legacy double-sends each
timer command (`bluetooth.tcl:1331, 1359, 1385`) for v1.0 firmware —
Crema should follow suit unless we gate on firmware version.

### Crema implementation gap

`/Users/adrianmaceiras/code/crema/core/de1-scale/src/decent_scale.rs`
today exposes the codec (`parse_weight`, `tare`, `timer_*`,
`display_on_grams`) but is missing:

1. `display_on_ounces()` — `command(0x0A, [0x01, 0x01, 0x01, 0x01])`
   → `03 0A 01 01 01 01 09`.
2. `display_off()` — `command(0x0A, [0x00, 0x00, 0x00, 0x00])`
   → `03 0A 00 00 00 00 09`.
3. `power_off()` — `command(0x0A, [0x02, 0x00, 0x00, 0x00])`
   → `03 0A 02 00 00 00 0B`.
4. `heartbeat()` — `command(0x0A, [0x03, 0xFF, 0xFF, 0x00])`
   → `03 0A 03 FF FF 00 0A`.

These four constants are pure additions to the codec module; no
existing tests need to change. A higher-level `DecentScale`
capability struct (parallel to Bookoo's) does not yet exist next to
`decent_scale.rs` — only the byte-level codec lives there. PR E
needs to add that struct, wiring in: heartbeat task at 2 s cadence,
LCD-enable on machine `Idle`, LCD-disable on `Sleep`, and a tare
counter that increments per call.

### Things to verify against a real scale before shipping

- The "require heartbeat" byte5 flag in LCD-enable — confirm that
  setting it (`03 0A 01 01 00 01 08`) on current firmware does not
  break the on-scale display.
- Power-off command (`0x0A 0x02`) — confirms the v1.2+ firmware
  gate; on a v1.0/v1.1 scale this byte may be ignored silently.
- Heartbeat cadence — verify the 2 s recommendation against actual
  notification delivery; if FFF4 stops emitting type-`0x0A` frames in
  response, fall back to legacy's 1 s.
- Tare counter rollover behaviour — legacy starts at 253 and
  increments; verify the scale tolerates wrap-around at 255→0.
- Double-send pattern — measure whether current Decent Scale
  firmware still drops commands; if not, drop the double-send to
  halve write traffic.

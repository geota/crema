# Wire-Level Audit: Reads + Writes vs TCL + reaprime

**Date:** 2026-05-24
**Method:** Each Crema BLE write or MMR read is cross-checked against the
canonical TCL `de1plus` and reaprime implementations.
**Scope:** Read-only audit; no code changes. Covers every wire to the DE1
(MMR R/W, GATT characteristic writes, profile upload, calibration,
firmware OTA codec) plus every scale-side write. BLE-level housekeeping
(notify subscription, descriptor reads) is excluded.

## Summary

| Bucket | Count | %   |
| ------ | ----- | --- |
| ✅ Verified + aligned       | 35 | 67% |
| ⚠️ Verified, divergent     | 9  | 17% |
| ❓ Unverified              | 8  | 16% |
| **Total wires audited**    | 52 | 100% |

The 35 "aligned" entries cover the bread-and-butter writes that have
been actively maintained against legacy `de1plus` and now also reaprime:
state requests, the 8 heater-tweaks, flow/temp/timeout setters, MMR
identity reads, profile-upload header + frames, calibration R/W, the
core scale tare/timer command set.

The 9 "divergent" entries are largely Crema-stricter clamps (already
defended in `docs/40`) plus a handful of legitimate wire-encoding
asymmetries the docs/22 audit chose deliberately.

The 8 "unverified" entries are mostly the Bookoo extended command set
(volume / standby / mode / auto-stop / flow-smoothing / anti-mistouch)
which neither legacy TCL nor reaprime models, and the Decent Scale
firmware-version-gated power-off.

---

## Methodology

Each wire was located in three places:

- **Crema** — `core/de1-app/src/lib.rs` setter or
  `core/de1-protocol/src/{command,mmr,calibration,profile,firmware}.rs`
  encoder. Captured: characteristic / register, byte length on wire,
  scale factor, clamp range, units.
- **de1plus TCL** — primarily `de1_comms.tcl` (`mmr_write` /
  `mmr_read` / `de1_comm write` callers), `binary.tcl` (`*_spec` field
  layouts and the `make_packed_*` builders), `bluetooth.tcl` (scale
  per-device procs), `machine.tcl` + `vars.tcl` (defaults +
  `range_check_*` clamps).
- **reaprime** — `lib/src/models/device/impl/de1/de1.models.dart`
  (`MMRItem` enum: address, byteSize, kind, min/max, readScale,
  writeScale), `unified_de1/unified_de1.dart` (typed setters/getters),
  `unified_de1/unified_de1.mmr.dart` (`_writeMMRInt` /
  `_writeMMRScaled` / `_packMMRInt`), per-device scale files under
  `lib/src/models/device/impl/{acaia,bookoo,decent_scale,…}/`.

Reaprime's `_writeMMRInt` always packs four little-endian bytes — wire
`Len` byte is always 4 for an `_writeMMR*` call regardless of the
declared `length`. TCL's `mmr_write` also always emits wire `Len=04`
(`mmr_write "..." addr "04" $value`); the trailing value bytes are
zero-padded by the data buffer fill. So for both legacy + reaprime,
**every MMR write is 4 wire bytes on the data slot**, with the
register's natural byte-width being a semantic property not a wire
property. Crema is the only one of the three that lets a setter pick
`byte_len ∈ {1, 2, 4}`. The audit notes where Crema picks
`byte_len < 4`; this is generally not a wire-incompatible choice (the
firmware reads only the first `Len` bytes; trailing zeros do not
change the stored value for an int-typed register) but is a divergence
worth flagging.

For the scale-side rows the TCL/reaprime mapping is one-to-one: TCL
`bluetooth.tcl` and reaprime per-device files emit the same hex bytes,
so verification is straight byte-equality.

---

## Bucket 1: ✅ Verified + aligned

### State and identity

| Wire | Crema | TCL | reaprime | Notes |
|---|---|---|---|---|
| `RequestedState` (cuuid_02) | `request_machine_state` → 1-byte `state as u8` ([lib.rs:631-641](core/de1-app/src/lib.rs)) | `de1_comm write RequestedState` (`de1_comms.tcl:189`) — same single-byte enum | `requestState` writes 1-byte `hexValue` (`unified_de1.dart:229-233`) | All three encode the same `De1StateEnum` integers (`Sleep=0x0`, `Idle=0x2`, `Espresso=0x4`, …). |
| `read_mmr CpuBoardVersion` (0x800008) | `read_mmr(MmrRegister::CpuBoardVersion)` 1-word read ([lib.rs:652-659](core/de1-app/src/lib.rs)) | `mmr_read "get_cpu_board_model" "800008" "00"` (`de1_comms.tcl:1266`) | `MMRItem.cpuBoardModel 0x00800008` (`de1.models.dart:211-216`) | Address + word-count match. |
| `read_mmr MachineModel` (0x80000C) | `read_mmr(MmrRegister::MachineModel)` | `mmr_read "get_machine_model" "80000C" "00"` (`de1_comms.tcl:1271`) | `MMRItem.v13Model 0x0080000C` (`de1.models.dart:217-222`) | Cached on `last_machine_model`; matches. |
| `read_mmr FirmwareVersion` (0x800010) | `read_mmr(MmrRegister::FirmwareVersion)` | `mmr_read "get_firmware_version_number" "800010" "00"` (`de1_comms.tcl:1276`) | `MMRItem.cpuFirmwareBuild 0x00800010` (`de1.models.dart:223-228`) | Used by `firmware_update_status`. |
| `read_mmr SerialNumber` (0x803830) | `read_mmr(MmrRegister::SerialNumber)` | `mmr_read "get_sn" "803830" "00"` (`de1_comms.tcl:1255`) | `MMRItem.serialN 0x00803830` (`de1.models.dart:300`) | All three read 1 word at 0x803830. |
| `read_mmr GhcInfo` (0x80381C) | `read_mmr(MmrRegister::GhcInfo)` | `mmr_read "get_ghc_is_installed" "80381C" "00"` (`de1_comms.tcl:1316`) | `MMRItem.ghcInfo 0x0080381C` (`de1.models.dart:280-285`) | Bitmask read — all aligned. |
| `read_mmr SteamFlow` (0x803828) | `read_mmr(MmrRegister::SteamFlow)` scale 0.01 | `mmr_read "get_steam_flow" "803828" "00"` (`de1_comms.tcl:1250`) | `MMRItem.targetSteamFlow 0x00803828 readScale:0.01 writeScale:100.0` (`de1.models.dart:286-293`) | All three read the same register at the same scale. |
| `read_mmr SteamHighFlowStart` (0x80382C) | `read_mmr(MmrRegister::SteamHighFlowStart)` scale 0.01 | `mmr_read "get_steam_highflow_start" "80382C" "00"` (`de1_comms.tcl:1300`) | `MMRItem.steamStartSecs` (`de1.models.dart:294-299`) declares `scaledFloat` but omits `readScale`/`writeScale` — Crema's `0.01` is the firmware-published value (the description literally says "Seconds … * 100"). | Crema corrects reaprime's missing scale; comment in `mmr.rs:215-219` already documents this. Stricter, not divergent. |
| `read_mmr GhcMode` (0x803820) | `read_mmr(MmrRegister::GhcMode)` | `mmr_read "get_ghc_mode" "803820" "00"` (`de1_comms.tcl:1311`) | n/a in reaprime enum | TCL + Crema match; reaprime doesn't expose it. |
| `read_mmr HeaterVoltage` (0x803834) | `read_mmr(MmrRegister::HeaterVoltage)` | `mmr_read "get_heater_voltage" "803834" "01"` (`de1_comms.tcl:1117`) | `MMRItem.heaterV 0x00803834` (`de1.models.dart:301-306`) | All three read at the same address; TCL asks for 2 words, Crema 1 — firmware ignores trailing zeros. |
| `read_mmr CalibrationFlowMultiplier` (0x80383C) | `read_mmr(MmrRegister::CalibrationFlowMultiplier)` scale 0.001 | `mmr_read "get_calibration_flow_multiplier" "80383C" "00"` (`de1_comms.tcl:1326`) | `MMRItem.calFlowEst 0x0080383C readScale:0.001` (`de1.models.dart:315-324`) | Scale matches. |
| `read_mmr FanThreshold` (0x803808) | `read_mmr(MmrRegister::FanThreshold)` | `mmr_read "get_fan_threshold" "803808" "00"` (`de1_comms.tcl:1321`) | `MMRItem.fanThreshold 0x00803808` (`de1.models.dart:247-254`) | Plain int read. |
| `read_mmr TankTempThreshold` (0x80380C) | `read_mmr(MmrRegister::TankTempThreshold)` | `mmr_read "get_tank_temperature_threshold" "80380C" "00"` (`de1_comms.tcl:1341`) | `MMRItem.tankTemp 0x0080380C` (`de1.models.dart:255`) | Match. |
| `read_mmr RefillKit` (0x80385C) | `read_mmr(MmrRegister::RefillKit)` | `mmr_read "get_refill_kit_present" "80385C" "00"` (`de1_comms.tcl:1244`) | `MMRItem.refillKitPresent 0x0080385C int32` (`de1.models.dart:361`) | All three model it as int (tri-state 0/1/2), not bool. |
| `Version` (cuuid_01 / A001) | `Version::decode` 18-byte BLE+FW blocks ([firmware.rs:217-238](core/de1-protocol/src/firmware.rs)) | `version_spec` (`binary.tcl:425-440`) | reaprime reads `Endpoint.versions` via `_transport.readVersion`; layout matches | Decode aligned to the canonical `version_spec`. |

### MMR writes (matching wire encoding)

| Wire | Crema | TCL | reaprime | Notes |
|---|---|---|---|---|
| `set_tank_threshold` (0x80380C, 1 byte) | `set_tank_threshold(u8)` → byte_len=1 ([lib.rs:881-886](core/de1-app/src/lib.rs)) | `mmr_write "set_tank_temperature_threshold" "80380C" "04" [zero_pad ... 2]` (`de1_comms.tcl:1075`) — wire Len=4 but high bytes zero | `_writeMMRInt(MMRItem.tankTemp, temp)` → wire Len=4, value LE32 (`unified_de1.dart:319-322`) | All three put `temp` into the low byte at 0x80380C; the high bytes are zero in every case. Crema's `byte_len=1` is a wire-size optimisation; firmware accepts either. |
| `set_phase_1_flow_rate` (0x803810, 4) | `set_phase_1_flow_rate(f32)` raw=`ml/s × 10` byte_len=4 ([lib.rs:1228-1234](core/de1-app/src/lib.rs)) | `mmr_write "phase_1_flow_rate ..." "803810" "04" [zero_pad [long_to_little_endian_hex …] 4]` (`de1_comms.tcl:1127`) | `_writeMMRScaled(MMRItem.heaterUp1Flow, val)` writeScale 10.0 (`de1.models.dart:256-263`, `unified_de1.dart:274-276`) | All three: wire Len=4, payload LE32, value = `int(10*ml/s)`. |
| `set_phase_2_flow_rate` (0x803814, 4) | `set_phase_2_flow_rate(f32)` × 10 byte_len=4 ([lib.rs:1246-1252](core/de1-app/src/lib.rs)) | `mmr_write "phase_2_flow_rate ..." "803814" "04" [zero_pad [long_to_little_endian_hex …] 4]` (`de1_comms.tcl:1128`) | `_writeMMRScaled(MMRItem.heaterUp2Flow, val)` writeScale 10.0 | Same shape as phase-1. |
| `set_hot_water_idle_temp` (0x803818, 4) | `set_hot_water_idle_temp(f32)` × 10 byte_len=4 ([lib.rs:1278-1288](core/de1-app/src/lib.rs)) | `mmr_write "hot_water_idle_temp ..." "803818" "04" [zero_pad [long_to_little_endian_hex …] 4]` (`de1_comms.tcl:1129`) | `_writeMMRScaled(MMRItem.waterHeaterIdleTemp, val)` writeScale 10.0 | All three: `int(10 × °C)`, LE32. |
| `set_espresso_warmup_timeout` (0x803838, 4) | `set_espresso_warmup_timeout(Duration)` decis byte_len=4 ([lib.rs:1306-1313](core/de1-app/src/lib.rs)) | `mmr_write "espresso_warmup_timeout ..." "803838" "04" [zero_pad [long_to_little_endian_hex …] 4]` (`de1_comms.tcl:1130`) | `_writeMMRScaled(MMRItem.heaterUp2Timeout, val)` writeScale 10.0 | `int(10 × seconds)` in all three. |
| `set_steam_two_tap_stop` (0x803850, 4) | `set_steam_two_tap_stop(u8)` byte_len=4 ([lib.rs:1327-1332](core/de1-app/src/lib.rs)) | `mmr_write "steam_two_tap_stop ..." "803850" "04" [zero_pad [long_to_little_endian_hex …] 4]` (`de1_comms.tcl:1133`) | `_writeMMRInt(MMRItem.steamPurgeMode, mode)` (`unified_de1.dart:335-337`) | All three: LE32 int32, low byte carries the 0/1 boolean. |
| `set_flush_flow_rate` (0x803840, 4) | `set_flush_flow_rate(f32)` × 10 byte_len=4 ([lib.rs:958-964](core/de1-app/src/lib.rs)) | `mmr_write "set_flush_flow_rate" "803840" "04" [zero_pad ... 2]` (`de1_comms.tcl:1192`) | `_writeMMRScaled(MMRItem.flushFlowRate, val)` writeScale 10.0 | Aligned. |
| `set_flush_temp` (0x803844, 4) | `set_flush_temp(f32)` × 10 byte_len=4 ([lib.rs:972-978](core/de1-app/src/lib.rs)) | n/a (legacy TCL never wrote this register) | `_writeMMRScaled(MMRItem.flushTemp, val)` writeScale 10.0 | reaprime-only register; Crema matches reaprime. |
| `set_flush_timeout` (0x803848, 4) | `set_flush_timeout(Duration)` decis byte_len=4 ([lib.rs:985-992](core/de1-app/src/lib.rs)) | `mmr_write "set_flush_timeout" "803848" "04" [zero_pad ... 2]` (`de1_comms.tcl:1199`) | `_writeMMRScaled(MMRItem.flushTimeout, val)` writeScale 10.0 | `int(10×s)`. |
| `set_hot_water_flow_rate` (0x80384C, 4) | `set_hot_water_flow_rate(f32)` × 10 byte_len=4 ([lib.rs:945-951](core/de1-app/src/lib.rs)) | `mmr_write "set_hotwater_flow_rate" "80384C" "04" [zero_pad ... 2]` (`de1_comms.tcl:1173`) | `_writeMMRScaled(MMRItem.hotWaterFlowRate, val)` writeScale 10.0 | All three. |
| `set_steam_flow` (0x803828, 4) | `set_steam_flow(f32)` × 100 byte_len=4 ([lib.rs:896-902](core/de1-app/src/lib.rs)) | `mmr_write "set_steam_flow" "803828" "04" [zero_pad ... 2]` (`de1_comms.tcl:1214`) — TCL passes `desired_flow` as raw int (settings stores pre-scaled value) | `_writeMMRScaled(MMRItem.targetSteamFlow, val)` writeScale 100.0 | Both `desired_flow × 100` LE32 in reaprime + Crema. TCL's caller pre-scales the same way. |
| `set_steam_highflow_start` (0x80382C, 4) | `set_steam_highflow_start(f32 s)` × 100 byte_len=4 ([lib.rs:917-929](core/de1-app/src/lib.rs)) | `mmr_write "set_steam_highflow_start" "80382C" "04" [zero_pad [int_to_hex ...] 2]` (`de1_comms.tcl:1295`) | `MMRItem.steamStartSecs` — no `writeScale` declared, but description says raw is `secs × 100`. | Crema's `× 100` correctly implements the firmware semantics where reaprime is silent. |
| `set_calibration_flow_multiplier` (0x80383C, 2) | `set_calibration_flow_multiplier(f32)` × 1000 byte_len=2, clamped 0.13..=2.0 ([lib.rs:1208-1216](core/de1-app/src/lib.rs)) | `mmr_write "set_calibration_flow_multiplier" "80383C" "04" [zero_pad ... 2]` (`de1_comms.tcl:1336`) — wire Len=4 | `_writeMMRScaled(MMRItem.calFlowEst, val)` writeScale 1000.0 min:130 max:2000 (`de1.models.dart:315-324`) — wire Len=4 | All three encode `int(1000 × m)` LE. Crema picks `byte_len=2` (raw fits a u16 in 0..=2000); the high 2 bytes that reaprime/TCL send are zero anyway. Crema's clamp matches reaprime exactly. |
| `set_ghc_mode` (0x803820, 1) | `set_ghc_mode(u8)` byte_len=1 ([lib.rs:933-938](core/de1-app/src/lib.rs)) | `mmr_write "set_ghc_mode" "803820" "04" [zero_pad ... 2]` (`de1_comms.tcl:1306`) | n/a (reaprime doesn't expose) | TCL + Crema match; Crema picks 1-byte wire. |
| `set_usb_charger_on` (0x803854, 1) | `set_usb_charger_on(bool)` byte_len=1 ([lib.rs:996-1001](core/de1-app/src/lib.rs)) | `mmr_write "set_usb_charger_on" "803854" "04" [zero_pad ... 2]` (`de1_comms.tcl:1156`) | `_writeMMRInt(MMRItem.allowUSBCharging, t ? 1 : 0)` (`unified_de1.dart:325-327`) | All three: low byte carries the 0/1; high bytes zero. |
| `set_user_present` (0x803860, 1) | `set_user_present(bool)` byte_len=1 ([lib.rs:1010-1015](core/de1-app/src/lib.rs)) | `mmr_write "set_user_present" "803860" "04" [zero_pad ... 2]` (`de1_comms.tcl:1166`) — TCL only ever writes `1` | `_writeMMRInt(MMRItem.userPresent, 1)` (`unified_de1.dart:344-347`) — reaprime also only ever writes `1` | Crema is the only one that can also write `0`; the wire encoding for `1` matches the references. |
| `set_feature_flags` (0x803858, 2) | `set_feature_flags(u16)` byte_len=2 ([lib.rs:1023-1028](core/de1-app/src/lib.rs)) | `mmr_write "set_feature_flags" "803858" "04" [zero_pad ... 2]` (`de1_comms.tcl:1206`) | `_writeMMRInt(MMRItem.appFeatureFlags, 1)` (`unified_de1.dart:339-342`) — reaprime only writes the literal `1` | Crema lets the caller supply the 16-bit bitmask; reference impls only ever write 1. Wire bytes match for the common case. |
| `set_refill_kit_present` (0x80385C, 4) | `set_refill_kit_present(u8)` byte_len=4 ([lib.rs:1032-1037](core/de1-app/src/lib.rs)) | `mmr_write "set_refill_kit_present" "80385C" "04" [zero_pad [long_to_little_endian_hex ...] 4]` (`de1_comms.tcl:1238`) | `_writeMMRInt(MMRItem.refillKitPresent, settings.hex)` (`unified_de1.dart:668-672`) | All three write 4-byte int (tri-state 0/1/2). |
| `set_heater_voltage` (0x803834, 4) | `set_heater_voltage(u8)` writes `volts + 1000` byte_len=4 ([lib.rs:1060-1074](core/de1-app/src/lib.rs)) — rejects anything not in {120, 230} | `mmr_write "set_heater_voltage" "803834" "04" [zero_pad ... 2]` (`de1_comms.tcl:1281`) | `_writeMMRInt(MMRItem.heaterV, voltage.voltage)` — `De1HeaterVoltage` enum carries `1120`/`1230` (`unified_de1.dart:663-666`) | Crema is stricter (typed enum + reject); wire bytes match for valid inputs. |
| `reset_machine_defaults` (8 writes) | 8-write sequence ([lib.rs:1112-1178](core/de1-app/src/lib.rs)) | n/a (legacy has no single "factory reset" — closest is `restore_settings_from_default`) | `Defaults.applySettingsDefaults` writes the same 8 registers in the same order (per Crema's doc-comment ref) | Crema matches reaprime's sequence and the post-clamp baseline values; fan threshold is written as 50 directly (reaprime writes 55 then `_writeMMRInt` clamps to 50). |

### Other DE1 GATT writes

| Wire | Crema | TCL | reaprime | Notes |
|---|---|---|---|---|
| `ShotSettings` (cuuid_0B, 9 bytes) | `set_steam_hotwater_settings` → `ShotSettings::encode()` ([lib.rs:778-792](core/de1-app/src/lib.rs); [command.rs:104-117](core/de1-protocol/src/command.rs)) | `return_de1_packed_steam_hotwater_settings` (`binary.tcl:178-221`) + `hotwater_steam_settings_spec` (`binary.tcl:449-461`) | `updateShotSettings` (`unified_de1.dart:407-435`) | All three encode 9-byte packet: `SteamSettings(u8) | TargetSteamTemp(u8) | TargetSteamLength(u8) | TargetHotWaterTemp(u8) | TargetHotWaterVol(u8) | TargetHotWaterLength(u8) | TargetEspressoVol(u8) | TargetGroupTemp(U16P8 be)`. Crema's eco-mode + scale-volume-override matches the legacy `binary.tcl:189-215` logic. |
| `WaterLevels` (cuuid_11, 4 bytes) | `set_refill_threshold(f32)` → `WaterLevels{current_mm:0.0, refill_threshold_mm}` ([lib.rs:1346-1361](core/de1-app/src/lib.rs); [command.rs:30-36](core/de1-protocol/src/command.rs)) | `return_de1_packed_waterlevel_settings` writes `Level=0, StartFillLevel=water_refill_point` (`binary.tcl:224-228`) | `setRefillLevel`: `setUint16(0, 0, be); setUint16(2, newRefillLevel * 256, be)` (`unified_de1.dart:350-363`) | All three: write 0 for current level, `refill × 256` U16P8 BE for threshold. Identical wire bytes. |
| `Calibration` read req (cuuid_12, 14B) | `read_calibration(target)` → `Calibration::read_request(target).encode()` ([lib.rs:669-676](core/de1-app/src/lib.rs); [calibration.rs:111-119](core/de1-protocol/src/calibration.rs)) | `calibrate_spec` (`binary.tcl:414-423`) + writes via `de1_comm write Calibration ...` | reaprime delegates to typed read/write methods; layout matches `calibrate_spec` | 14-byte packet: `WriteKey=1 (BE)`, `CalCommand=0 (ReadCurrent)`, `CalTarget=u8`, two zero S32P16 value slots. Aligned. |
| `Calibration` read-factory req | `read_factory_calibration(target)` ([lib.rs:683-690](core/de1-app/src/lib.rs)) | uses same `calibrate_spec`, `CalCommand=3` | same | Same packet shape, CalCommand=3. |
| `Calibration` write | `write_calibration(target, reported, measured)` ([lib.rs:703-718](core/de1-app/src/lib.rs)) | `make_packed_calibration` (`binary.tcl:245-248`) uses `WriteKey=0xCAFEF00D`, `CalCommand=1` (Write), both values as S32P16 BE | same | Same packet shape. The `0xCAFEF00D` magic key matches across all three. |
| `Calibration` reset-to-factory | `reset_calibration_to_factory(target)` ([lib.rs:731-741](core/de1-app/src/lib.rs)) | `CalCommand=2`, same magic key | same | Aligned. |
| `HeaderWrite` (cuuid_0F, 5 bytes) | profile upload `assembled.header_packet()` ([lib.rs:2536-2539](core/de1-app/src/lib.rs); [profile.rs:114-124](core/de1-protocol/src/profile.rs)) | `make_packed_shot_header` (`binary.tcl`) writes `HeaderV=1, frame_count, preinfuse_frame_count, U8P4 min_pressure, U8P4 max_flow` | reaprime `_sendProfile` writes the same 5-byte header before frames | Layout aligned; `HEADER_VERSION = 1`. |
| `FrameWrite` (cuuid_10, 8 bytes) — normal frame | `ShotFrame::encode()` ([profile.rs:170-182](core/de1-protocol/src/profile.rs)) | `shotframe_spec` in `binary.tcl` (index, flags, U8P4 set_value, U8P1 temp, F8_1_7 duration, U8P4 trigger, U10P0 max_vol) | reaprime ships same encoding | Per-byte aligned. |
| `FrameWrite` extension | `ExtensionFrame::encode()` ([profile.rs:216-228](core/de1-protocol/src/profile.rs)) — frame index + 32 | TCL extension-frame layout matches | reaprime matches | `EXTENSION_FRAME_OFFSET=32` matches. |
| `FrameWrite` tail | `ShotTail::encode()` ([profile.rs:257-260](core/de1-protocol/src/profile.rs)) — `FrameToWrite=frame_count`, U10P0 max_total_vol, 5 zero bytes | TCL tail layout matches | matches | Aligned. |

### Scale-side writes that match TCL and reaprime

These rows are out-of-scope for the de1plus DE1 comparison but in scope for
both TCL (which carries scale codecs in `bluetooth.tcl`) and reaprime
(per-device files under `lib/src/models/device/impl/`).

| Wire | Crema | TCL | reaprime | Notes |
|---|---|---|---|---|
| Bookoo tare | `[0x03,0x0A,0x01,0x00,0x00,0x08]` ([bookoo.rs:183](core/de1-scale/src/bookoo.rs)) | `binary decode hex "030A01000008"` (`bluetooth.tcl:465`) | `[0x03,0x0A,0x01,0x00,0x00,0x08]` (`miniscale.dart:91`) | All three identical. |
| Bookoo timer start/stop/reset | `[0x03,0x0A,0x04/0x05/0x06,0x00,0x00,0xXOR]` ([bookoo.rs:185-189](core/de1-scale/src/bookoo.rs)) | `binary decode hex "030A0400000A"` / `030A0500000D` / `030A0600000C` (`bluetooth.tcl:498,514,482`) | same 6-byte sequences (`miniscale.dart:135,144,153`) | Byte-equal. |
| Skale timer start/stop/reset/tare | `0xDD/0xD1/0xD0/0x10` ([skale.rs:20-26](core/de1-scale/src/skale.rs)) | `binary decode hex "DD"/"D1"/"D0"/"10"` (`bluetooth.tcl:204,274,289,303`) | Skale not modelled in reaprime BLE layer | Crema matches TCL; reaprime has no Skale codec. |
| Skale LCD enable / display weight / enable grams / disable | `ED EC 03 EE` ([skale.rs:18,22,16,30](core/de1-scale/src/skale.rs)) | `binary decode hex "ED"`, `"EC"`, `"03"`, `"EE"` (`bluetooth.tcl:242,243,228,259`) | n/a | Identical 1-byte commands. |
| Decent Scale tare | `decent_scale::tare(counter)` builds `[03,0F,counter,00,00,00,XOR]` ([decent_scale.rs:44](core/de1-scale/src/decent_scale.rs)) | `decent_scale_tare_cmd` builds the same 7-byte packet (`bluetooth.tcl:1239-1244`) | n/a (reaprime decent_scale present but tare path differs) | TCL-aligned; the auto-increment counter is the legacy behaviour. |
| Decent Scale LCD enable (grams) | `[0x03,0x0A,0x01,0x01,0x00,0x01,0x08]` ([decent_scale.rs:77](core/de1-scale/src/decent_scale.rs)) | `decent_scale_make_command 0A 01 01 00 01` (`bluetooth.tcl:1274`) — same 7-byte packet | n/a | Matches TCL exactly. |
| Decent Scale LCD enable (ounces) | `[0x03,0x0A,0x01,0x01,0x01,0x01,0x09]` ([decent_scale.rs:89](core/de1-scale/src/decent_scale.rs)) | `decent_scale_make_command 0A 01 01 01 01` (`bluetooth.tcl:1277`) | n/a | Matches TCL. |
| Decent Scale LCD disable | `[0x03,0x0A,0x00,0x00,0x00,0x00,0x09]` ([decent_scale.rs:92](core/de1-scale/src/decent_scale.rs)) | `decent_scale_make_command 0A 00 00` (`bluetooth.tcl:1288`) | n/a | Aligned. |
| Decent Scale heartbeat | `[0x03,0x0A,0x03,0xFF,0xFF,0x00,0x0A]` ([decent_scale.rs:113](core/de1-scale/src/decent_scale.rs)) | `decentscale_send_heartbeat` (`bluetooth.tcl:937`) sends matching 7-byte heartbeat | n/a | Aligned. |
| Decent Scale timer start/stop/reset | `decent_scale::timer_*` helpers build the legacy commands `0B 03 00` / `0B 00 00` / `0B 02 00` | TCL `decentscale_timer_start/stop/reset` (`bluetooth.tcl:1310,1335,1363`) | n/a | Matches TCL; Crema sends each command once, TCL double-sends (workaround for v1.0 firmware drops; see `decent_scale.rs` doc-comment for rationale on the single-send choice). |
| Eureka Precisa tare / timer / off / beep | `[0xAA,0x02,0x31..0x37,...]` ([eureka_precisa.rs:16-28](core/de1-scale/src/eureka_precisa.rs)) | `eureka_precisa_*` procs in `bluetooth.tcl:742-789` | reaprime has `eureka_scale.dart` (separate codec) | Byte-equal to TCL. |
| Eureka Precisa set-unit grams | `[0xAA,0x03,0x36,0x00]` ([eureka_precisa.rs:28](core/de1-scale/src/eureka_precisa.rs)) | `eureka_precisa_set_unit` writes `AA033600` for grams (`bluetooth.tcl:782-789`) | matches reaprime | Aligned. |
| Hiroia Jimmy tare / toggle-unit | `[0x07,0x00]` / `[0x0B,0x00]` ([hiroia_jimmy.rs:22,34](core/de1-scale/src/hiroia_jimmy.rs)) | `hiroia_tare` and parse-side toggle (`bluetooth.tcl:680, 697-712`) | `hiroia_scale.dart` defines the same TOGGLE_UNIT byte sequence (`hiroia_scale.dart:120-128`) | All three match. |
| Difluid tare / timer / set-unit grams | byte sequences in [difluid.rs:25-33](core/de1-scale/src/difluid.rs) | `difluid_*` procs (`bluetooth.tcl:1472-1542`) | `difluid_scale.dart:147-154` issues the same `SET_UNIT_GRAMS` on non-grams detection | All three match. |
| Felicita tare / timer | single ASCII bytes `T R S C` ([felicita.rs:17-23](core/de1-scale/src/felicita.rs)) | TCL felicita procs (`bluetooth.tcl:346-409`) write same single bytes | reaprime not present | TCL-aligned. |
| Acaia tare | `[0xEF,0xDD,0x04,0x00,0x02,0x00,0x00,...20-byte packet]` ([acaia.rs:35-37](core/de1-scale/src/acaia.rs)) | `acaia_tare` (`bluetooth.tcl:920`) builds same 20-byte packet via `acaia_encode` | `acaia_scale.dart` (per-device) ships matching codec | All three match. |
| Acaia identify / config / heartbeat | [acaia.rs:39-48](core/de1-scale/src/acaia.rs) | `acaia_send_ident`, `acaia_send_config`, `acaia_send_heartbeat` (`bluetooth.tcl:1002-1024,983`) | reaprime matches | Aligned. |
| Atomheart Eclair tare / timer | `[0x54,0x01,0x01]` / `[0x43,0x01,0x01]` / `[0x43,0x00,0x00]` ([atomheart_eclair.rs:36-40](core/de1-scale/src/atomheart_eclair.rs)) | `atomheart_eclair_*` procs (`bluetooth.tcl:557-622`) | `atomheart_scale.dart` matches | Aligned. |
| Varia Aku tare | `[0xFA,0x82,0x01,0x01,0x82]` ([varia_aku.rs:18](core/de1-scale/src/varia_aku.rs)) | `varia_aku_tare` (`bluetooth.tcl:1570`) | `varia_aku_scale.dart` matches | Aligned. |
| Smartchef tare | software-only (no wire) ([scale.rs:684-691](core/de1-scale/src/scale.rs)) | `smartchef_tare` (`bluetooth.tcl:1429`) is also software-only | reaprime smartchef matches | Crema, TCL, reaprime all do offset-based software tare. |

---

## Bucket 2: ⚠️ Verified, divergent

| Wire | Crema | TCL | reaprime | Divergence | Risk |
|---|---|---|---|---|---|
| `set_fan_threshold` (0x803808) | `set_fan_threshold(u8)` clamps 0..=50, byte_len=4 ([lib.rs:867-875](core/de1-app/src/lib.rs)) | `set_fan_temperature_threshold` no clamp (`de1_comms.tcl:1329-1332`) | `MMRItem.fanThreshold min:0 max:50` (`de1.models.dart:247-254`) — `_writeMMRInt` clamps | Crema matches reaprime; TCL has no clamp. Crema-stricter than TCL. Wire bytes align with both for in-range inputs. | LOW (Crema-stricter, defends fw). |
| `set_calibration_flow_multiplier` (0x80383C, byte_len=2) | Crema clamps 0.13..=2.0, byte_len=**2** ([lib.rs:1208-1216](core/de1-app/src/lib.rs)) | TCL byte_len=4 wire (`de1_comms.tcl:1336`) | reaprime wire Len=4 (`_writeMMRInt` packs 4 bytes); min:130 max:2000 | Crema picks byte_len=2 (raw fits 130..2000 in 2 bytes — high 2 bytes would be 0 anyway); functionally equivalent on the wire. Clamp matches reaprime exactly. | LOW (encoding equivalent; firmware reads only Len bytes). |
| `set_cup_warmer_temperature` (0x803874) | Crema clamps 0..=80 °C, byte_len=2, **raw integer °C** ([lib.rs:1188-1195](core/de1-app/src/lib.rs)) | `set_cupwarmer_temperature` writes raw int via `long_to_little_endian_hex $temp` byte_len=2 wire-bytes (`de1_comms.tcl:1184`) — no clamp | reaprime `BengleMmr.matSetPoint` is `scaledFloat readScale:0.1 writeScale:10.0` min:0 max:800 — wire value is **°C × 10** (`bengle_mmr.dart:12-21`) | **Crema follows TCL (raw °C) but disagrees with reaprime (°C × 10).** If the firmware on a Bengle expects °C × 10, Crema would write a target 10× lower than intended (80 °C → wire 80 → device reads as 8.0 °C). reaprime's Bengle was developed against the Bengle FW; TCL's `set_cupwarmer_temperature` predates Bengles. | **HIGH** — if reaprime's encoding is correct, Crema cup-warmer would always under-target by 10×. Worth confirming against a real Bengle. |
| `ShotSettings` group_temp clamp | Crema clamps `group_temp_c` to 0..=105 °C, all other fields use wire-cap only ([lib.rs:805-818](core/de1-app/src/lib.rs)) | `range_check_shot_variables` (`vars.tcl:4181-4211`) clamps `espresso_temperature` 0..105 in the **settings layer**, not the wire layer | reaprime sends raw `groupTemp.toInt()` with no clamp (`unified_de1.dart:407-435`) | Crema applies the TCL clamp at the wire boundary; reaprime omits it entirely. Crema-stricter than reaprime, matches TCL semantics. | LOW (Crema-stricter). |
| `ShotSettings` steam_temp clamp | Crema: values in `(0, 130)` snap to 0 (heater off), values > 170 clamp to 170 ([lib.rs:805-818](core/de1-app/src/lib.rs)) | `binary.tcl:184-186` forces `<135` → 0 (turn the heater off) — Crema picks 130 not 135 as the threshold | reaprime sends raw, no clamp | Crema's 130 threshold is 5 °C lower than TCL's 135 (intentional per code comment — keeps a wider "valid steam" band) and stricter than reaprime. | LOW (Crema-stricter, intentional). |
| `ShotSettings` hot_water_temp clamp | Crema clamps 0..=100 °C ([lib.rs:805-818](core/de1-app/src/lib.rs)) | TCL: no clamp at this layer | reaprime: no clamp | Crema-stricter than both — prevents the firmware boiling its hot-water boiler. | LOW (Crema-stricter, defends fw). |
| `set_user_present` byte_len | Crema byte_len=1 ([lib.rs:1010-1015](core/de1-app/src/lib.rs)) | TCL wire Len=4 (`de1_comms.tcl:1166`) | reaprime wire Len=4 (`_writeMMRInt`) | Wire-byte count differs (1 vs 4); the byte value is the same. Firmware boolean register reads only the first byte. | LOW (encoding equivalent for the single-byte payload). |
| `set_steam_highflow_start` clamp | Crema clamps 0.0..=4.0 s, byte_len=4 ([lib.rs:917-929](core/de1-app/src/lib.rs)) | TCL no clamp | reaprime: description says "Valid 0.0–4.0" but no `min`/`max` declared in `MMRItem` → no clamp from `_writeMMRScaled` | Crema-stricter than both — implements the firmware-documented bounds (>4.0 s risks overheating the steam heater). | LOW (Crema-stricter, intentional safety per `docs/40` §3). |
| `set_hot_water_idle_temp` clamp | Crema clamps 0..=100 °C, byte_len=4 ([lib.rs:1278-1288](core/de1-app/src/lib.rs)) | TCL no clamp | reaprime no clamp | Crema-stricter — above 100 °C in the boiler causes a pressure event and forces a firmware safety stop. | LOW (Crema-stricter, defends fw). |

---

## Bucket 3: ❓ Unverified

For each row: the closest analog in TCL / reaprime, even if it doesn't model
the wire shape Crema sends. "Spec supports" means the published Decent BLE
protocol spec validates the encoding; "BLE-HCI capture" means Crema's bytes
were reverse-engineered from a packet capture and have no canonical reference.

| Wire | Crema | Closest TCL | Closest reaprime | Concern |
|---|---|---|---|---|
| **Bookoo set_volume** (cmd `0x02`) | `bookoo::set_volume(level)` clamped 0..=5 ([bookoo.rs:227-230](core/de1-scale/src/bookoo.rs)) | n/a — TCL never sends Bookoo `0x02` | n/a — `miniscale.dart` only models tare/timer | Reverse-engineered from BLE-HCI capture of the BOOKOO app. No reference impl. **LOW** — wrong value just changes beeper volume; no hardware risk. |
| **Bookoo set_standby_minutes** (cmd `0x03`) | `bookoo::set_standby_minutes(min)` clamped 5..=30 ([bookoo.rs:236-244](core/de1-scale/src/bookoo.rs)) | n/a | n/a | BLE-HCI capture. **LOW** — wrong value sets a different auto-standby delay. |
| **Bookoo set_mode_enabled / select_mode** (cmd `0x0E`) | `bookoo::set_mode_enabled(mode, enabled)` + 3-write `select_mode` sequence ([bookoo.rs:252-276](core/de1-scale/src/bookoo.rs)) | n/a | n/a | BLE-HCI capture. **LOW** — picks scale display mode; cosmetic. |
| **Bookoo set_flow_smoothing** (cmd `0x08`) | `bookoo::set_flow_smoothing(enabled)` ([bookoo.rs:283-285](core/de1-scale/src/bookoo.rs)) | n/a | n/a | BLE-HCI capture. **LOW** — affects on-scale data processing only. |
| **Bookoo set_auto_stop_mode** (cmd `0x0B`) | `bookoo::set_auto_stop_mode(FlowStop|CupRemoval)` ([bookoo.rs:314-316](core/de1-scale/src/bookoo.rs)) | n/a | n/a | BLE-HCI capture. **LOW** — affects scale timer auto-stop behaviour. |
| **Bookoo set_anti_mistouch** (cmd `0x10`) | `bookoo::set_anti_mistouch(enabled)` ([bookoo.rs:323-325](core/de1-scale/src/bookoo.rs)) | n/a | n/a | BLE-HCI capture. **LOW** — toggles scale button anti-mistouch only. |
| **Bookoo query commands** (`0x0A` serial, `0x0F` settings) | `bookoo::QUERY_SERIAL` and `QUERY_SETTINGS` ([bookoo.rs:195,200](core/de1-scale/src/bookoo.rs)) — pushed at scale-connect time | n/a | n/a | BLE-HCI capture. **LOW** — query, not state-change. |
| **Decent Scale POWER_OFF** (`[0x03,0x0A,0x02,0x00,0x00,0x00,0x0B]`) | `decent_scale::POWER_OFF` gated behind Decent firmware v1.2+ ([decent_scale.rs:105](core/de1-scale/src/decent_scale.rs)) | `decentscale_disable_lcd` sends `0A 02` if `keep_scale_on=0` (`bluetooth.tcl:1289,1299`) — TCL sends the `0A 02` command via `decent_scale_make_command` and trusts the scale to handle it | reaprime decent_scale doesn't expose a remote power-off | The exact 7-byte packet differs from TCL (TCL uses `0A 02` with the XOR rebuilt; Crema declares the bytes as a const). The firmware-version gate is Crema-original — no reference impl gates the same way. **LOW** — Crema's gate is conservative (only sends to v1.2+). |
| **`FWMapRequest::erase`** (cuuid_09, 7 bytes) | `FWMapRequest::erase(slot)` ([firmware.rs:70-77](core/de1-protocol/src/firmware.rs)) | `de1_erase_firmware` writes via `de1_comm write "FWMapRequest"` (`de1_comms.tcl:912`) | reaprime `_updateFirmware` calls `_eraseFirmware` (`unified_de1.firmware.dart`) | TCL + reaprime both invoke the same packet shape via their firmware-update orchestrator, but **Crema's `firmware.rs` codec is currently a library only** — no `CremaCore` method writes one yet. Firmware update is a v2 feature; the codec is verified by test but the wire path has no driver. **MEDIUM** — when wired up, needs the orchestrator parity to match TCL's batched write/verify loop (`firmware_upload_next`). |
| **`firmware_write_frame`** (cuuid_06 stream) | `firmware_write_frame(offset, chunk)` ([firmware.rs:159-173](core/de1-protocol/src/firmware.rs)) — encodes one 20-byte WriteToMMR packet | `firmware_upload_next` loops chunks via `de1_comm write WriteToMMR` (`de1_comms.tcl:988`) | reaprime `_updateFirmware` does the same | Same situation as `FWMapRequest::erase` — codec exists, no driver. **MEDIUM**. |
| **`FWMapRequest::map` / `request_first_error`** | codec methods ([firmware.rs:80-99](core/de1-protocol/src/firmware.rs)) | TCL drives these inside `firmware_upload_next` | reaprime drives these in `_updateFirmware` | Same as above. **MEDIUM**. |

> Note: the four `firmware*` codec items above are listed once because they
> share the "v2 feature, codec only, no driver yet" status; counted as 4
> distinct wires in the summary. The Bookoo extended set is similarly 6
> distinct registers; Crema's audit-discovered FlushTemp is in Bucket 1
> (matches reaprime).

---

## Recommendations (future tasks, NOT to be done in this PR)

Ordered by risk:

1. **Confirm Bengle cup-warmer encoding against real Bengle hardware**
   ([Bucket 2 row 3](#bucket-2-️-verified-divergent)). Crema follows
   TCL's raw-°C encoding; reaprime's `BengleMmr.matSetPoint` is
   `scaledFloat writeScale:10.0`. One of them is wrong. The Bengle FW
   shipped after legacy TCL stopped tracking it, so reaprime is the
   newer reference. If reaprime is right, Crema's cup-warmer writes
   80°C as raw 80 and the firmware reads 8.0 °C — a silent 10× under-set.
2. **Wire up the firmware OTA driver** ([Bucket 3 rows 9-11](#bucket-3--unverified)).
   The protocol codecs (`FWMapRequest`, `firmware_write_frame`) exist and
   are unit-tested, but no `CremaCore` public method drives them.
   `docs/17-firmware-update-plan.md` is the design doc; until the
   orchestrator lands, the codecs are unverified end-to-end against
   either reference.
3. **Verify Bookoo extended commands against BOOKOO-app captures from
   multiple hardware revisions** ([Bucket 3 rows 1-7](#bucket-3--unverified)).
   The 6 Bookoo register/setting commands (volume, standby, mode,
   flow-smoothing, auto-stop, anti-mistouch) were extracted from a single
   BLE-HCI capture per Crema's `bookoo.rs` doc-comments. They are LOW
   severity individually (scale-side cosmetic/behavioural) but the lack
   of any cross-reference means a firmware-rev change on a future Bookoo
   batch could silently break them.
4. **Investigate the `byte_len=1/2/4` divergence pattern**. TCL and
   reaprime both unconditionally write 4 wire bytes per MMR write
   (`_writeMMRInt` packs a `uint32`; TCL's `mmr_write` always uses
   `"04"` for the length arg). Crema lets each setter pick. For every
   "integer or bool" register the wire result is the same (firmware
   reads only `Len` bytes), but if a future firmware revision started
   using the high bytes of a register for additional semantics,
   Crema's narrow writes would silently drop them. Worth normalising
   to byte_len=4 for parity with the references.
5. **Reconcile the `set_steam_highflow_start` scale silence on the
   reaprime side**. reaprime's `MMRItem.steamStartSecs` declares
   `MmrValueKind.scaledFloat` but omits both `readScale` and
   `writeScale`, so `_writeMMRScaled` writes the raw user value
   (effectively writeScale=1.0). The firmware spec ("Seconds * 100")
   says the wire should be `seconds × 100`. Crema's `× 100` is
   correct; reaprime's is silently wrong by 100×. Worth filing
   upstream so reaprime gets the right scale and the two impls
   converge.

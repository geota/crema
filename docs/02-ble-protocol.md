# 02 — DE1 Bluetooth LE Protocol Reference

Canonical protocol reference for the Decent Espresso DE1 machine, reverse-derived
from the Tcl tablet app (`de1app/de1plus/`). The Tcl source is the de-facto spec.

All `file:line` citations refer to files under `de1app/de1plus/`.

**Global endianness rule:** every DE1 GATT packet is **big-endian** ("network byte
order"). The Tcl parsers all pass `bigeendian` (sic — the typo is harmless; the
`fields::2form` parser only inspects the first character, `b`, see `binary.tcl:55`).
The only little-endian payloads are: (a) the firmware-file header on disk
(`binary.tcl:1148`), and (b) MMR data *values* written/read as 32-bit ints
(`spec_ReadFromMMR_int`, parsed `littleeendian`, `binary.tcl:1261`; written via
`long_to_little_endian_hex`, `de1_comms.tcl:1127+`). Scale protocols vary — see §8.

---

## 1. DE1 GATT Service and Characteristics

### Service

| Item | Value | Source |
|---|---|---|
| DE1 GATT service UUID (`suuid`) | `0000A000-0000-1000-8000-00805F9B34FB` | `machine.tcl:56` |
| 16-bit short form | `0xA000` | — |

All DE1 characteristics share the base `0000Axxx-0000-1000-8000-00805F9B34FB`
(Bluetooth SIG base UUID with a 16-bit value in the `Axxx` slot).

### Characteristics (`cuuid_01 .. cuuid_12`)

Names from `::de1_cuuids_to_command_names` (`machine.tcl:200-219`). Direction:
`W` = the app writes; `R` = the app reads; `N` = the app subscribes to
notifications. UUID column shows the 16-bit short value (full UUID =
`0000<short>-0000-1000-8000-00805F9B34FB`).

| Var | UUID | Command name | Dir | Purpose |
|---|---|---|---|---|
| `cuuid_01` | `A001` | `Version` | R | BLE + firmware version block. Parsed by `version_spec` (`binary.tcl:425`). |
| `cuuid_02` | `A002` | `RequestedState` | W | Write 1 byte = desired `MachineState` (see §4, §7). Also the value of bare `::de1(cuuid)` (`machine.tcl:58`). |
| `cuuid_03` | `A003` | `SetTime` | — | Commented out / unused (`machine.tcl:202`). |
| `cuuid_04` | `A004` | `ShotDirectory` | — | Commented out / unused (`machine.tcl:203`). |
| `cuuid_05` | `A005` | `ReadFromMMR` | W / N | MMR read. App **writes** a read-request (`spec_WriteToMMR`-shaped, 20 bytes), DE1 **notifies** back the data (`spec_ReadFromMMR`). See §6. |
| `cuuid_06` | `A006` | `WriteToMMR` | W | MMR write — 20-byte `spec_WriteToMMR` packet. See §6. |
| `cuuid_07` | `A007` | `ShotMapRequest` | — | Commented out / unused. |
| `cuuid_08` | `A008` | `DeleteShotRange` | — | Commented out / unused. |
| `cuuid_09` | `A009` | `FWMapRequest` | W / N | Firmware-update map request (`maprequest_spec`, 7 bytes). See §6 / firmware section. |
| `cuuid_0A` | `A00A` | `Temperatures` | R/N | Temperature readout. Not parsed by the app in current code (named only). |
| `cuuid_0B` | `A00B` | `ShotSettings` | R / W | Steam + hot-water + group-temp settings (`hotwater_steam_settings_spec`, 9 bytes). See §7. |
| `cuuid_0C` | `A00C` | `DeprecatedShotDesc` | W | Legacy single-packet whole-shot description (`shot_sample_spec`, header + 10 fixed frames). Superseded by Header/FrameWrite. See §5. |
| `cuuid_0D` | `A00D` | `ShotSample` | N | **The telemetry stream.** ~4–10 Hz notifications, parsed by `shotsample_parse`. See §3. |
| `cuuid_0E` | `A00E` | `StateInfo` | R / N | Machine state + substate (2 bytes). See §4. |
| `cuuid_0F` | `A00F` | `HeaderWrite` | R / W | Espresso profile **header** (`spec_shotdescheader`, 5 bytes). See §5. |
| `cuuid_10` | `A010` | `FrameWrite` | R / W | One espresso profile **frame** (8 bytes; `spec_shotframe` / `spec_extshotframe` / `spec_shottail`). See §5. |
| `cuuid_11` | `A011` | `WaterLevels` | R / W / N | Water-tank level + refill threshold (`waterlevel_spec`, 4 bytes). See §7. |
| `cuuid_12` | `A012` | `Calibration` | W / N | Sensor calibration read/write (`calibrate_spec`, 14 bytes). See §7. |

**There is no separate "MMR characteristic."** MMR access is layered over
`cuuid_05` (ReadFromMMR) and `cuuid_06` (WriteToMMR). See §6.

**Routing.** `de1_ble` (`bluetooth.tcl:2116`) maps a command *name* to its cuuid
via `::de1_command_names_to_cuuids` and issues `ble <action> <handle> <suuid>
<sinstance> <cuuid> <cinstance> [data]`. Actions: `read`, `write`, `enable`
(subscribe), `disable` (unsubscribe).

---

## 2. Fixed-Point Number Encodings

The DE1 uses several packed fixed-point formats. Names follow the convention
`<sign><int-bits>P<frac-bits>` where `P` = the radix point.
Conversion procs are in `binary.tcl`.

### Summary table

| Format | Width | Layout | Float → raw | Raw → float | Range | Source |
|---|---|---|---|---|---|---|
| `U8P0`  | 8b  | unsigned int, no fraction | `round(x)`, clamp ≤256 | `x` | 0–255 | `convert_float_to_U8P0` `binary.tcl:526` |
| `U8P1`  | 8b  | 7.1 unsigned fixed | `round(x*2)`, clamp ≤128 | `x/2.0` | 0–127.5 step 0.5 | `convert_float_to_U8P1` `binary.tcl:519` |
| `U8P4`  | 8b  | 4.4 unsigned fixed | `round(x*16)`, clamp ≤16 | `x/16.0` | 0–15.9375 step 0.0625 | `convert_float_to_U8P4` `binary.tcl:512` |
| `U16P8` | 16b | 8.8 unsigned fixed | `round(x*256)`, clamp ≤256 | `x/256.0` | 0–255.996 | `convert_float_to_U16P8` `binary.tcl:539` |
| `S32P16`| 32b | signed 16.16 fixed | `round(x*65536)`, clamp ≤65536 | `x/65536.0` | ±32768-ish | `convert_float_to_S32P16` `binary.tcl:546` |
| `F8_1_7`| 8b  | custom "1/7-bit float" | see below | see below | 0–127 | `convert_float_to_F8_1_7` `binary.tcl:553`, `convert_F8_1_7_to_float` `binary.tcl:490` |
| `U10P0` | "10-bit in 16-bit" | bottom 10 bits used, bit 10 = enable | `round(x) | 1024` | `x & 1023` | 0–1023 | `convert_float_to_U10P0` `binary.tcl:567`, `convert_bottom_10_of_U10P0` `binary.tcl:502` |
| `U24P0` | 24b | unsigned 24-bit int (3 bytes) | 3 bytes hi,mid,low | `c1*65536 + c2*256 + c3` | 0–16.7M | `make_U24P0` `binary.tcl:259`, `convert_3_char_to_U24P0` `binary.tcl:1347` |
| `U24P16`| 24b | unsigned 8.16 fixed (3 bytes) | — | `c1 + c2/256 + c3/65536` | 0–255.99998 | `convert_3_char_to_U24P16` `binary.tcl:1343` |
| `U32P0` | 32b | unsigned 32-bit int (4 bytes) | 4 bytes | `c0*2^24 + c1*2^16 + c2*256 + c3` | 0–4.29G | `make_U32P0` `binary.tcl:250`, `convert_4_char_to_U32P0` `binary.tcl:1351` |

### Details

**U8P4 (4.4 unsigned).** One byte. High nibble = integer part (0–15),
low nibble = sixteenths. `value = raw / 16.0`. Used for pressure and flow
setpoints in shot frames, and `MinimumPressure` / `MaximumFlow` in the header.
Encoder clamps input to 16 before scaling.

**U8P1 (7.1 unsigned).** One byte. `value = raw / 2.0` → temperature in 0.5 °C
steps, 0–127.5 °C. Used for `Temp` in shot frames. Encoder clamps to 128.

**U16P8 (8.8 unsigned).** Two bytes big-endian. `value = raw / 256.0`.
Used for `TargetGroupTemp` in ShotSettings and for water level. The
`waterlevel_spec` extra-expr divides by 256.0 (`binary.tcl:443-444`).

**S32P16 (signed 16.16).** Four bytes big-endian, signed. `value = raw / 65536.0`.
Used in the calibration packet for `DE1ReportedVal` and `MeasuredVal`.
Note `calibrate_spec` declares `DE1ReportedVal` as **unsigned** Int but
`MeasuredVal` as **signed** Int (`binary.tcl:419-420`) — see §7 ambiguity note.

**F8_1_7 — the "1/7-bit float" (8-bit, frame length).** One byte. Two regimes
selected by the **high bit (0x80)**:

- If bit 7 == 0: `value = raw / 10.0` → 0.0 .. 12.7 in 0.1-second steps.
- If bit 7 == 1: `value = raw & 0x7F` → 0 .. 127 in whole-second steps.

Decoder `convert_F8_1_7_to_float` (`binary.tcl:490`):
```
highbit = in & 0x80
if highbit == 0: out = in / 10.0
else:            out = in & 0x7F
```
Encoder `convert_float_to_F8_1_7` (`binary.tcl:553`):
```
if x >= 12.75:  out = round(x) | 0x80      # clamp x to 127 first
else:           out = round(x * 10)
```
This format gives fine 0.1 s resolution for short frames and coarse 1 s
resolution for long frames, in a single byte. Used for `FrameLen`.

**U10P0 — 10-bit value carried in a 16-bit field.** A `Short` (2 bytes BE).
The bottom 10 bits hold the value (0–1023). **Bit 10 (0x400 = 1024) is an
"enabled / use this value" flag**, OR-ed in by the encoder. Decoder masks with
`& 0x3FF` (`convert_bottom_10_of_U10P0`). Used for `MaxVol` (per-frame volume
limit) and `MaxTotalVolume` in the shot tail. A raw value of 0 (without the
1024 bit) means "ignore."

**U24P0 / U24P16 (24-bit, 3 bytes).** Tcl has no 24-bit int, so the head
temperature in ShotSample is read as three separate bytes and recombined.
`U24P0` = pure integer; `U24P16` = 8.16 fixed (`hi + mid/256 + low/65536`),
used for `HeadTemp` (see §3).

**U32P0 (32-bit, 4 bytes).** Used by `make_U32P0` / `convert_4_char_to_U32P0`,
primarily for MMR 32-bit register values and firmware-header fields.

---

## 3. ShotSample Packet (`cuuid_0D` / `A00D`) — Telemetry Stream

The DE1 notifies this characteristic at the firmware shot-reporting rate
(~4–10 Hz; tied to line frequency, half-cycle counted — see §3.2). Parsed by
`::de1::packet::shotsample_parse` (`de1_de1.tcl:680-764`). **Big-endian.**

### 3.1 Current layout ("new BLE spec", 19 bytes)

`spec` at `de1_de1.tcl:712-726`. HeadTemp is a 24-bit value read as 3 bytes.

| Off | Field | Type | Width | Decode (raw→value) | Units |
|---|---|---|---|---|---|
| 0  | `SampleTime`       | U16 | 2 | raw | half-cycles counter (see §3.2) |
| 2  | `GroupPressure`    | U16 | 2 | `raw / 4096.0` | bar |
| 4  | `GroupFlow`        | U16 | 2 | `raw / 4096.0` | mL/s |
| 6  | `MixTemp`          | U16 | 2 | `raw / 256.0` | °C |
| 8  | `HeadTemp1`        | U8  | 1 | integer part | °C (combine w/ 9,10) |
| 9  | `HeadTemp2`        | U8  | 1 | /256 fraction | — |
| 10 | `HeadTemp3`        | U8  | 1 | /65536 fraction | — |
| 11 | `SetMixTemp`       | U16 | 2 | `raw / 256.0` | °C |
| 13 | `SetHeadTemp`      | U16 | 2 | `raw / 256.0` | °C |
| 15 | `SetGroupPressure` | U8  | 1 | `raw / 16.0` | bar |
| 16 | `SetGroupFlow`     | U8  | 1 | `raw / 16.0` | mL/s |
| 17 | `FrameNumber`      | U8  | 1 | raw | current shot frame index |
| 18 | `SteamTemp`        | U8  | 1 | raw | °C |

`HeadTemp` is then computed as `U24P16`:
`HeadTemp = HeadTemp1 + HeadTemp2/256.0 + HeadTemp3/65536.0`
(`convert_3_char_to_U24P16`, applied at `de1_de1.tcl:758`).

**Total length: 19 bytes.** The parser rejects packets shorter than 7 bytes
(`de1_de1.tcl:687`).

### 3.2 Legacy layout ("old BLE spec")

`spec_old` at `de1_de1.tcl:695-707`. In the old layout `GroupPressure` /
`GroupFlow` are single `U8P4` bytes (`raw/16.0`), `MixTemp`/`HeadTemp` are
`U16P8` shorts (`raw/256.0`), and `SteamTemp` is a `U16P8` short.
The app auto-detects: if it parses with the *new* spec and gets no
`SteamTemp` field it sets `::de1::packet::ble_spec = 0.9` and re-parses with the
old spec thereafter (`de1_de1.tcl:744-751`). **For a Rust rewrite targeting
current firmware, implement the new 19-byte layout; the legacy path is a
fallback for very old machines.**

### 3.3 SampleTime semantics

`SampleTime` is a free-running 16-bit counter incremented per AC half-cycle
(100 Hz @ 50 Hz mains, 120 Hz @ 60 Hz mains). It **wraps at 65536**. The app
unwraps deltas (`de1_de1.tcl:558-569`): `dhc = sample - prev; if dhc < 0: dhc +=
65536`, then `intersample_time = dhc / (2 * line_frequency)`. Line frequency is
either configured or estimated from arrival timing (`de1_de1.tcl:324-391`).
Flow is integrated into dispensed volume using this interval.

Fields surfaced to the rest of the app: `pressure`, `flow`, `mix_temperature`,
`head_temperature`, `goal_temperature` (=SetHeadTemp), `goal_pressure`,
`goal_flow`, `current_frame_number`, `steam_heater_temperature`
(`de1_de1.tcl:535-544`).

---

## 4. StateInfo Packet (`cuuid_0E` / `A00E`) — State Update

2-byte packet, parsed by `parse_state_change` (`binary.tcl:1447-1465`):

| Off | Field | Type | Meaning |
|---|---|---|---|
| 0 | `state`    | U8 | `MachineState` enum (table below) |
| 1 | `substate` | U8 | `SubState` enum (table below) |

Empty/short messages are ignored (`update_de1_state`, `binary.tcl:1479`).

### 4.1 MachineState enum

From `::de1_state` / `::de1_num_state` (`machine.tcl:511-560`):

| Value | Name | Notes |
|---|---|---|
| 0  | `Sleep` | |
| 1  | `GoingToSleep` | |
| 2  | `Idle` | |
| 3  | `Busy` | |
| 4  | `Espresso` | flow state |
| 5  | `Steam` | flow state |
| 6  | `HotWater` | flow state |
| 7  | `ShortCal` | |
| 8  | `SelfTest` | |
| 9  | `LongCal` | |
| 10 | `Descale` | |
| 11 | `FatalError` | |
| 12 | `Init` | |
| 13 | `NoRequest` | used as a "no-op" in RequestedState |
| 14 | `SkipToNext` | command: advance to next espresso frame |
| 15 | `HotWaterRinse` | flush; flow state |
| 16 | `SteamRinse` | |
| 17 | `Refill` | |
| 18 | `Clean` | |
| 19 | `InBootLoader` | |
| 20 | `AirPurge` | |
| 21 | `SchedIdle` | scheduled-wake idle; firmware ≥ v1293 only (`machine.tcl:1090`) |

"Flow states" (`Espresso`, `Steam`, `HotWater`, `HotWaterRinse`) drive
flow-phase logic (`de1_de1.tcl:435-465`).

### 4.2 SubState enum

From `::de1_substate_types` (`machine.tcl:568-609`). `-` is a non-numeric
sentinel meaning "starting"; numeric values are what the firmware actually sends.

| Value | Name | Description (`machine.tcl:613-652`) |
|---|---|---|
| 0  | `ready` | State not relevant |
| 1  | `heating` | Heating cold water tank |
| 2  | `final heating` | Warm up heater for shot |
| 3  | `stabilising` | Stabilize mix temp / water path |
| 4  | `preinfusion` | Espresso preinfusion (flow phase "during") |
| 5  | `pouring` | Water flowing (flow phase "during") |
| 6  | `ending` | Flush valve active; espresso only (flow phase "after") |
| 7  | `Steaming` | Steam only |
| 8  | `DescaleInit` | Starting descale |
| 9  | `DescaleFillGroup` | Descaling solution into group |
| 10 | `DescaleReturn` | Descaling internals |
| 11 | `DescaleGroup` | Descaling group |
| 12 | `DescaleSteam` | Descaling steam |
| 13 | `CleanInit` | Starting clean |
| 14 | `CleanFillGroup` | Fill the group |
| 15 | `CleanSoak` | 60 s soak |
| 16 | `CleanGroup` | Flush through group |
| 17 | `refill` | Refill given up? |
| 18 | `PausedSteam` | Paused in steam |
| 19 | `UserNotPresent` | |
| 20 | `puffing` | |
| 200 | `Error_NaN` | Died with a NaN |
| 201 | `Error_Inf` | Died with an Inf |
| 202 | `Error_Generic` | |
| 203 | `Error_ACC` | Accelerometer not responding/unlocked |
| 204 | `Error_TSensor` | Temperature sensor error |
| 205 | `Error_PSensor` | Pressure sensor error |
| 206 | `Error_WLevel` | Water level sensor error |
| 207 | `Error_DIP` | DIP switches force error state |
| 208 | `Error_Assertion` | Assertion failed |
| 209 | `Error_Unsafe` | Unsafe value assigned |
| 210 | `Error_InvalidParm` | Invalid parameter |
| 211 | `Error_Flash` | External flash access error |
| 212 | `Error_OOM` | Out of memory |
| 213 | `Error_Deadline` | Realtime deadline missed |
| 214 | `Error_HiCurrent` | Current out of bounds |
| 215 | `Error_LoCurrent` | Not enough current |
| 216 | `Error_BootFill` | Boot pressure test failed (no water?) |
| 217 | `Error_NoAC` | Front power switch off |

Flow-phase mapping (`de1_de1.tcl:450-464`): `starting/ready/heating/final
heating/stabilising` → "before"; `preinfusion/pouring` → "during"; `ending` →
"after".

---

## 5. Espresso Profile / Shot-Frame Upload Format

A profile is uploaded as: **one header packet** to `HeaderWrite` (`cuuid_0F`),
then **N frame packets** to `FrameWrite` (`cuuid_10`) — one BLE write per frame.
The whole encode pipeline is `de1_packed_shot` (`binary.tcl:864`) →
`make_chunked_packed_shot_sample` (`binary.tcl:839`), driven by
`de1_send_shot_frames` (`de1_comms.tcl:1439`).

Three frame variants share the 8-byte `FrameWrite` slot, distinguished by the
first byte `FrameToWrite`:

- **Normal frame** — `FrameToWrite` 0..31 → `spec_shotframe`.
- **Extension frame** — `FrameToWrite` 32..63 (= `index + 32`) → `spec_extshotframe`.
  Provides "advanced" max-flow/max-pressure limiting.
- **Tail frame** — last frame, `FrameToWrite = NumberOfFrames` → `spec_shottail`.

The decoder picks the variant by testing `FrameToWrite >= 32`
(`parse_binary_shotframe`, `binary.tcl:687`).

### 5.1 Header — `spec_shotdescheader` (5 bytes, `binary.tcl:786`)

C struct `T_ShotDesc` header (`binary.tcl:1038-1045`).

| Off | Field | Type | Decode | Meaning |
|---|---|---|---|---|
| 0 | `HeaderV` | U8 | raw | Header version — always `1` (`binary.tcl:866`) |
| 1 | `NumberOfFrames` | U8 | raw | Total number of frames |
| 2 | `NumberOfPreinfuseFrames` | U8 | raw | How many leading frames count as preinfusion |
| 3 | `MinimumPressure` | U8P4 | `raw/16` | Min pressure allowed in flow-priority modes |
| 4 | `MaximumFlow` | U8P4 | `raw/16` | Max flow allowed in pressure-priority modes |

### 5.2 Normal frame — `spec_shotframe` (8 bytes, `binary.tcl:797`)

C struct `T_ShotFrame` (`binary.tcl:1049-1056`).

| Off | Field | Type | Decode | Meaning |
|---|---|---|---|---|
| 0 | `FrameToWrite` | U8 | raw | Frame index (0-based) |
| 1 | `Flag` | U8 | bitfield | Frame flags — see §5.4 |
| 2 | `SetVal` | U8P4 | `raw/16` | Target pressure (bar) **or** flow (mL/s), per `CtrlF` flag |
| 3 | `Temp` | U8P1 | `raw/2` | Target temperature, 0.5 °C steps |
| 4 | `FrameLen` | F8_1_7 | see §2 | Frame duration in seconds |
| 5 | `TriggerVal` | U8P4 | `raw/16` | Exit-comparison threshold (pressure or flow) |
| 6 | `MaxVol` | U10P0 | `raw & 0x3FF` | Per-frame volume limit, mL; 0 = ignore; bit 10 = enable |

### 5.3 Extension frame — `spec_extshotframe` (8 bytes, `binary.tcl:810`)

Emitted only when the profile step sets `max_flow_or_pressure`
(`binary.tcl:971-983`). The "advanced shot" limiting feature.

| Off | Field | Type | Decode | Meaning |
|---|---|---|---|---|
| 0 | `FrameToWrite` | U8 | raw | `index + 32` |
| 1 | `MaxFlowOrPressure` | U8P4 | `raw/16` | Limit value (flow if frame is pressure-priority, or vice versa) |
| 2 | `MaxFoPRange` | U8P4 | `raw/16` | Tolerance band around the limit |
| 3–7 | `Pad1..Pad5` | U8 | 0 | Zero padding |

### 5.4 Frame `Flag` bitfield — `T_E_FrameFlags`

From the C enum (`binary.tcl:572-588`) and `make_shot_flag` / `parse_shot_flag`
(`binary.tcl:591-653`):

| Bit | Mask | Name | Meaning |
|---|---|---|---|
| 0 | `0x01` | `CtrlF` | 1 = flow priority, 0 = pressure priority (`CtrlP`) |
| 1 | `0x02` | `DoCompare` | Enable early-exit comparison for this frame |
| 2 | `0x04` | `DC_GT` | Comparison direction: 1 = exit if `>` threshold, 0 = `<` (`DC_LT`) |
| 3 | `0x08` | `DC_CompF` | Compare against flow (1) vs pressure (0, `DC_CompP`) |
| 4 | `0x10` | `TMixTemp` | Target mix temp instead of basket temp (disables showerhead comp) |
| 5 | `0x20` | `Interpolate` | Ramp smoothly to target (vs hard jump) |
| 6 | `0x40` | `IgnoreLimit` | Ignore min-pressure / max-flow header limits |

Notes from the encoder (`binary.tcl:906-985`):
- `IgnoreLimit` is **always set** by the current app (it does not enforce
  header limits) — `set features {IgnoreLimit}`.
- Profile-step `pump == "flow"` → adds `CtrlF`, uses `flow` as `SetVal`;
  otherwise uses `pressure`.
- `sensor == "water"` → adds `TMixTemp`.
- `transition == "smooth"` → adds `Interpolate`.
- `exit_if == 1` with `exit_type`: `pressure_under` → `DoCompare`;
  `pressure_over` → `DoCompare|DC_GT`; `flow_under` → `DoCompare|DC_CompF`;
  `flow_over` → `DoCompare|DC_GT|DC_CompF`.
- **End-of-shot rule** (C comment, `binary.tcl:572`): `Flag == 0` *and*
  `pressure == 0` means end of shot, unless it is the tenth frame.

There is no explicit "FROTH" bit in this source. The flag set is exactly the
seven bits above.

### 5.5 Tail frame — `spec_shottail` (8 bytes, `binary.tcl:824`)

Always appended as the final frame (`binary.tcl:1000-1006`).

| Off | Field | Type | Decode | Meaning |
|---|---|---|---|---|
| 0 | `FrameToWrite` | U8 | raw | `= NumberOfFrames` |
| 1 | `MaxTotalVolume` | U10P0 | `raw & 0x3FF` | Whole-shot volume limit; high bit toggles preinfusion tracking (currently always 0) |
| 2–6 | `Pad1..Pad5` | U8 | 0 | Zero padding |

### 5.6 Send sequence

`de1_send_shot_frames` (`de1_comms.tcl:1439`):
1. Clear stale `Espresso header:` / `Espresso frame #` entries from the BLE queue.
2. Write the header to `HeaderWrite`.
3. Write each frame (normal frames, then extension frames, then the tail) to
   `FrameWrite`, one BLE write each.
4. For advanced profiles set the tank temperature MMR; then run a confirmation
   step checking that every frame index was ACKed (`confirm_de1_send_shot_frames_worked`).

The legacy single-packet path (`DeprecatedShotDesc` / `cuuid_0C`) encodes header
+ 10 fixed frames into one buffer via `shot_sample_spec` (`binary.tcl:1059`);
not used by current code.

---

## 6. MMR — Memory-Mapped Registers

The DE1 exposes internal config/state through a memory-mapped register window.
Access is layered over two characteristics:

- **`WriteToMMR` (`cuuid_06`)** — write a 20-byte packet to set a register.
- **`ReadFromMMR` (`cuuid_05`)** — the app *writes* a 20-byte read request
  (length + address, data bytes zero); the DE1 then *notifies* `cuuid_05` with
  the same shape plus the data filled in.

MMR is only available on BLE API ≥ 4; the app gates this in `mmr_available`
(`de1_comms.tcl:736-761`).

### 6.1 Packet layout — `spec_WriteToMMR` / `spec_ReadFromMMR` (20 bytes)

C structs `T_WriteToMMR` / `T_ReadFromMMR` (`binary.tcl:707-712`, `752-757`).

| Off | Field | Type | Meaning |
|---|---|---|---|
| 0 | `Len` | U8 | **Read:** length to read, in words minus 1 (0 → 4 bytes, 1 → 8 bytes, … 255 → 1024 bytes). **Write:** length of data in bytes. |
| 1–3 | `Address` | U24P0 | MMR address, big-endian 3 bytes (`Address1` hi … `Address3` low) |
| 4–19 | `Data[16]` | U8×16 | Up to 16 data bytes, zero-padded |

`mmr_read` (`de1_comms.tcl:1000`) builds: `len_byte + 3-byte-addr + 16 zero
bytes`. `mmr_write` (`de1_comms.tcl:1025`) builds: `len_byte + 3-byte-addr +
value-bytes + zero pad`. On reply, `parse_binary_mmr_read` reconstructs the
hex address string; `parse_binary_mmr_read_int` re-reads `Data` as **little-endian
32-bit ints** (`spec_ReadFromMMR_int`, `binary.tcl:738`) — register values are
little-endian 32-bit words.

### 6.2 `maprequest_spec` (`FWMapRequest` / `cuuid_09`, 7 bytes, `binary.tcl:401`)

Used for firmware update mapping/erase, not general MMR.

| Off | Field | Type | Meaning |
|---|---|---|---|
| 0–1 | `WindowIncrement` | U16 | Auto-increment window size |
| 2 | `FWToErase` | U8 | Firmware slot to erase (1 = erase) |
| 3 | `FWToMap` | U8 | Firmware slot to map |
| 4–6 | `FirstError1..3` | U8×3 | First-error address; `FF FF FF` = request, `FF FF FD` = "no error" reply (`de1_comms.tcl:487-489`) |

### 6.3 Known MMR register addresses

Addresses are 24-bit hex. Word size is 4 bytes; values little-endian.
Decode handlers: `de1_comms.tcl:284-440`. Base region `0x800000` = hardware
info, `0x803800+` = tunable config.

| Address | Words | Name | Meaning / Source |
|---|---|---|---|
| `800000` | 1 | HWConfig / CPU board model | CPU board model ×1000 (1100 = v1.1). `de1_comms.tcl:392,1099` |
| `800004` | 1 | Model | Reserved Model word (`de1_comms.tcl:1100`) |
| `800008` | 1–3 | CPU board model + machine model + FW version | Reading 3 words at once yields CPU board model, machine model (0=unset,1=DE1,2=Plus,3=Pro,4=XL,5=Cafe), and FW build number (`de1_comms.tcl:392-414`) |
| `80000C` | 1 | Machine model | v1.3+ firmware model enum (`de1_comms.tcl:416-421`) |
| `800010` | 1 | Firmware version number | FW build number, starts at 1000 for v1.3 (`de1_comms.tcl:423-428`) |
| `802800` | 1 | Debug buffer valid count | Chars valid in debug buffer; access pauses BLE logging (`de1_comms.tcl:1101`) |
| `802804` | 0x1000 | Debug buffer | Last 4 KB of debug output (`de1_comms.tcl:1102`) |
| `803808` | 1 | Fan threshold | Fan-on temperature threshold °C (`de1_comms.tcl:309-312,1103`) |
| `80380C` | 1 | Tank water temperature threshold | Tank desired temp; written by `set_tank_temperature_threshold` (`de1_comms.tcl:314,1075`) |
| `803810` | 1 | phase_1_flow_rate | Hot-water phase-1 flow (`de1_comms.tcl:353,1127`) |
| `803814` | 1 | phase_2_flow_rate | Hot-water phase-2 flow (`de1_comms.tcl:1128`) |
| `803818` | 1 | hot_water_idle_temp | Hot water idle temperature (`de1_comms.tcl:343,1129`) |
| `80381C` | 1 | GHC Info bitmask | Group Head Controller: bit0 = GHC present, bit1 = GHC active (`de1_comms.tcl:297,1105`) |
| `803820` | 1 | GHC mode | Group head control mode (`de1_comms.tcl:318`) |
| `803828` | 4 | Steam flow | Steam flow rate, wire = mL/s × 100 (`de1_comms.tcl:322,1214`; reaprime `steamFlow writeScale 100.0`) |
| `80382C` | 4 | steam_highflow_start | Seconds of high-flow steam at start, wire = seconds × 100 (legacy default `machine.tcl:309 steam_highflow_start 70` = 0.7s) |
| `803830` | 1 | Serial number | Machine SN (`de1_comms.tcl:326,1255`) |
| `803834` | 1–2 | Heater voltage (+ warmup timeout) | Mains voltage; second word = espresso warmup timeout (`de1_comms.tcl:366-389,1117`) |
| `803838` | 1 | espresso_warmup_timeout | Espresso warmup timeout (`de1_comms.tcl:349,1130`) |
| `80383C` | 1 | Calibration flow multiplier | `int(1000 * multiplier)` (`de1_comms.tcl:1336`) |
| `803840` | 1 | Flush flow rate | `int(10 * rate)` (`de1_comms.tcl:1192`) |
| `803848` | 1 | Flush timeout | `int(10 * seconds)` (`de1_comms.tcl:1199`) |
| `80384C` | 1 | Hot water flow rate | `int(10 * rate)` (`de1_comms.tcl:1173`) |
| `803850` | 1 | steam_two_tap_stop | "SteamPurgeMode" — 1 = two taps to stop steam (`de1_comms.tcl:1133`) |
| `803854` | 1 | USB charger on | 1 = tablet USB charging on (`de1_comms.tcl:1156`) |
| `803858` | 1 | Feature flags | e.g. UserNotPresent flag (`de1_comms.tcl:1206`) |
| `80385C` | 1 | Refill kit present | 0/1/2 (2 = auto) (`de1_comms.tcl:338,1238`) |
| `803860` | 1 | User present | Write 1 to signal user present (`de1_comms.tcl:1166`) |
| `803874` | 1 | Cupwarmer temperature | Bengle models only (`de1_comms.tcl:434,1184`) |

> **Ambiguity:** the comment block at `de1_comms.tcl:1094-1107` lists only a
> partial set; addresses above `0x803820` are inferred from the setter/getter
> procs. Word counts for the multi-word reads (`800008`, `803834`, `803810`)
> depend on the `Len` requested. A future implementer should confirm against
> the official DE1 firmware MMR map.

---

## 7. Commands the App Sends

### 7.1 RequestedState (`cuuid_02` / `A002`)

A **single byte** = the desired `MachineState` value (§4.1). Sent by
`de1_send_state` (`de1_de1.tcl` → `de1_comms.tcl:1375`), e.g.
`de1_send_state "make espresso" $::de1_state(Espresso)` writes byte `0x04`.

> **Ambiguity / caution:** In simulation paths the Tcl appends a second byte
> (e.g. `"$::de1_state(Refill)\x5"`, `machine.tcl:680`) but that goes to the
> local `update_de1_state` simulator, **not** over BLE. The real BLE
> `RequestedState` write is the single state byte. Verify packet length against
> firmware (expected: 1 byte).

### 7.2 Calibration (`cuuid_12` / `A012`) — `calibrate_spec` (14 bytes)

`calibrate_spec` (`binary.tcl:414`), built by `de1_send_calibration`
(`de1_comms.tcl:1586`).

| Off | Field | Type | Meaning |
|---|---|---|---|
| 0–3 | `WriteKey` | U32 (BE) | Magic key. `0xCAFEF00D` to write; `1` for a read request (`de1_comms.tcl:1605,1640`) |
| 4 | `CalCommand` | U8 | 0 = read current, 1 = write, 2 = reset to factory, 3 = read factory |
| 5 | `CalTarget` | U8 | 0 = flow, 1 = pressure, 2 = temperature |
| 6–9 | `DE1ReportedVal` | S32P16 | DE1's reported value `/65536` (declared *unsigned* in spec) |
| 10–13 | `MeasuredVal` | S32P16 | Externally measured value `/65536` (declared *signed*) |

> **Ambiguity:** `DE1ReportedVal` is declared `unsigned` and `MeasuredVal`
> `signed` in `calibrate_spec`, yet both are encoded with
> `convert_float_to_S32P16` (signed). Treat both as **signed 16.16** in Rust;
> the `unsigned` tag is likely a Tcl oversight.

### 7.3 Water level settings (`cuuid_11` / `A011`) — `waterlevel_spec` (4 bytes)

`waterlevel_spec` (`binary.tcl:441`), built by `return_de1_packed_waterlevel_settings`
(`binary.tcl:224`).

| Off | Field | Type | Decode | Meaning |
|---|---|---|---|---|
| 0–1 | `Level` | U16P8 | `raw/256` | Current level (write side sends 0; read side reports actual) |
| 2–3 | `StartFillLevel` | U16P8 | `raw/256` | Refill threshold (mm) from `water_refill_point` |

Same characteristic notifies the current tank level back; the app adds
`water_level_mm_correction` (5 mm) to the reported `Level` (`de1_comms.tcl:467`).

### 7.4 Steam / hot-water settings (`cuuid_0B` / `A00B`) — `hotwater_steam_settings_spec` (9 bytes)

`hotwater_steam_settings_spec` (`binary.tcl:449`), built by
`return_de1_packed_steam_hotwater_settings` (`binary.tcl:178`).

| Off | Field | Type | Meaning |
|---|---|---|---|
| 0 | `SteamSettings` | U8 | Bit flags (top bits 0x80/0x40 reserved; app currently sends 0) |
| 1 | `TargetSteamTemp` | U8P0 | Steam temp °C; 0 if heater disabled or temp < 135 °C |
| 2 | `TargetSteamLength` | U8P0 | Steam timeout seconds (0 → sent as 255) |
| 3 | `TargetHotWaterTemp` | U8P0 | Hot-water temp °C |
| 4 | `TargetHotWaterVol` | U8P0 | Hot-water volume mL (250 if a scale is attached, for stop-on-weight) |
| 5 | `TargetHotWaterLength` | U8P0 | Hot-water max time seconds |
| 6 | `TargetEspressoVol` | U8P0 | Typical espresso volume mL |
| 7–8 | `TargetGroupTemp` | U16P8 | Espresso group temp °C (`raw/256`) |

Other steam-related tunables (steam flow, high-flow start, flush flow/timeout,
hot-water flow) are **MMR writes**, not part of this packet — see §6.3.

---

## 8. Scale BLE Protocols (Summary)

Scale support lives in `bluetooth.tcl` and `device_scale.tcl`. UUIDs are in
`machine.tcl:73-110`. Each scale exposes a weight-notification characteristic;
the app converts to grams and calls `::device::scale::process_weight_update`.

### 8.1 Decent Scale — FULL DETAIL

| Item | Value | Source |
|---|---|---|
| Service (`suuid_decentscale`) | `0000FFF0-0000-1000-8000-00805F9B34FB` | `machine.tcl:80` |
| Read / notify (`cuuid_decentscale_read`) | `0000FFF4-0000-1000-8000-00805F9B34FB` | `machine.tcl:77` |
| Write (`cuuid_decentscale_write`) | `000036F5-0000-1000-8000-00805F9B34FB` | `machine.tcl:78` |
| Writeback / notify (`cuuid_decentscale_writeback`) | `83CDC3D4-3BA2-13FC-CC5E-106C351A9352` | `machine.tcl:79` |

**Weight notification** (on `FFF4`), parsed by `parse_decent_scale_recv`
(`binary.tcl:1381`). Packets are 7 bytes (`decent_scale_generic_read_spec`) or
10 bytes (`..._v12`). When `command` byte == `0xCE` or `0xCA` it is re-parsed as
a weight packet:

7-byte weight (`decent_scale_weight_read_spec2`, `binary.tcl:339`):

| Off | Field | Type | Meaning |
|---|---|---|---|
| 0 | `model` | U8 | Model byte (`0x03`) |
| 1 | `wtype` | U8 | Weight-packet type (`0xCE`/`0xCA`) |
| 2–3 | `weight` | S16 (BE, **signed**) | Weight ×10 → grams = `weight / 10.0` |
| 4–5 | `rate` | U16 (BE) | Weight-change rate |
| 6 | `xor` | U8 | XOR checksum |

10-byte weight (`decent_scale_weight_read_spec_v12`, `binary.tcl:350`): same
first 4 bytes, then `minutes`,`seconds`,`milliseconds` (U8 each), two unused
bytes, `xor`. Timestamp ms = `minutes*600 + seconds*10 + milliseconds`
(`binary.tcl:1410`). Final grams = `weight / 10.0` (`bluetooth.tcl:2752`).
Other `command` values: `0xAA` = button press, `0x0F` = tare ACK,
`0x0A` = LED/battery/FW-version reply.

**Commands** (written to `36F5`), built by `decent_scale_make_command`
(`bluetooth.tcl:1201`). Every command is **7 bytes**:
`03 <cmdtype> <d1> <d2> <d3> <d4> <xor>` where unused data bytes are `00` and
`xor = 0x03 ^ cmdtype ^ d1 ^ d2 ^ d3 ^ d4`. Known commands:

| cmdtype | Payload | Meaning |
|---|---|---|
| `0x0A` | `01 01 00 01` | LED/screen on, grams (last byte 1 = require heartbeat) |
| `0x0A` | `01 01 01 01` | LED on, ounces |
| `0x0A` | `00 00` | Screen off |
| `0x0A` | `02` | Power off |
| `0x0B` | `03 00` | Timer start |
| `0x0B` | `00 00` | Timer stop |
| `0x0B` | `02 00` | Timer reset |
| `0x0F` | `<ctr> 00 00 01` | Tare (`ctr` = rolling tare counter, `bluetooth.tcl:1242`) |

### 8.2 Skale II (Atomax) — FULL DETAIL

| Item | Value | Source |
|---|---|---|
| Service (`suuid_skale`) | `0000FF08-0000-1000-8000-00805F9B34FB` | `machine.tcl:76` |
| Command write (`cuuid_skale_EF80`) | `0000EF80-0000-1000-8000-00805F9B34FB` | `machine.tcl:73` |
| Weight notify (`cuuid_skale_EF81`) | `0000EF81-0000-1000-8000-00805F9B34FB` | `machine.tcl:74` |
| Button notify (`cuuid_skale_EF82`) | `0000EF82-0000-1000-8000-00805F9B34FB` | `machine.tcl:75` |

**Weight notification** (on `EF81`). Parsed at `bluetooth.tcl:2701`:
`binary scan $value cus1cu t0 t1 t2 t3 t4 t5`

| Off | Field | Type | Meaning |
|---|---|---|---|
| 0 | `t0` | U8 | Prefix/marker byte |
| 1–2 | `t1` | S16 **little-endian, signed** | Weight ×10 → grams = `t1 / 10.0` |
| 3 | `t3` | U8 | (unused in app) |
| 4–5 | `t4` | further bytes (`cu`) | (unused) |

Note: the Skale weight short is **little-endian** (`s` in the scan format),
unlike the DE1 and the Decent Scale. Grams = `t1 / 10.0` (`bluetooth.tcl:2702`).

**Commands** (single-byte writes to `EF80`):

| Byte | Meaning |
|---|---|
| `0x03` | Enable grams mode |
| `0xEC` | Display weight on LCD |
| `0x10` | Tare |
| `0xD0` | Timer reset |
| `0xD1` | Timer stop |
| (`screenon`/`screenoff`/`timeron` constants set elsewhere in `bluetooth.tcl`) |

### 8.3 Other supported scales (summary — each needs its own follow-up)

The following scales are supported; their full packet formats should be
extracted from `bluetooth.tcl` / `device_scale.tcl` before implementing.

| Scale | `scale_type` | Service UUID | Notify / Cmd UUIDs | Notes |
|---|---|---|---|---|
| Felicita Arc | `felicita` | `FFE0` | notify+cmd `FFE1` | Weight ~ `bluetooth.tcl:411` (`cucua1a6`); tare `0x54`, timer reset `0x43`, start `0x52` |
| Bookoo | `bookoo` | `00000FFE-…` | notify `FF11`, cmd `FF12` | — |
| Acaia (gen1/IPS) | `acaiascale` | `00001820-…` | char `00002A80-…` | `acaia_parse_response` |
| Acaia Pyxis | `acaiapyxis` | `49535343-FE7D-…` | status `…-1E4D-…`, cmd `…-8841-…` | proprietary framed protocol |
| Atomheart Eclair | `atomheart_eclair` | `B905EAEA-2E63-…` | notify `AD736C5F-…`, cmd `4F9A45BA-…` | XOR-checked; weight scan `ciIc` (`bluetooth.tcl:644`) |
| Eureka Precisa | `eureka_precisa` | `FFF0` | status `FFF1`, cmd `FFF2` | weight scan `cucucucui` (`bluetooth.tcl:700`) |
| Difluid Microbalance | `difluid` | `000000EE-…` | char `AA01` | — |
| Smartchef | `smartchef` | `FFF0` | status `FFF1`, cmd `FFF2` | — |
| Solo Barista (LSJ-001) | `solo_barista` | `FFF0` | status `FFF1`, cmd `FFF2` | weight scan `cucucu cu su cu su` (`bluetooth.tcl:793`) |
| Hiroia Jimmy | `hiroiajimmy` | `06C31822-…` | cmd `…1823-…`, status `…1824-…` | — |
| Varia Aku | `varia_aku` | `FFF0` | notify `FFF1`, cmd `FFF2` | `varia_aku_parse_response` |

> **Note:** several scales reuse the generic `0000FFF0/FFF1/FFF2` service —
> they are disambiguated by advertised device name during scan
> (`bluetooth.tcl:2190-2233`). A Rust implementation must key off the BLE
> advertised name, not the service UUID alone.

---

## 9. Rust Mapping Notes

### 9.1 Fixed-point types

Recommended approach: implement each format as a thin newtype or a pair of
free functions. All DE1 packets are big-endian; use `from_be_bytes` /
`to_be_bytes`. Endianness flags below call out the exceptions.

| Format | Rust raw type | Suggested API | Endianness |
|---|---|---|---|
| `U8P0`  | `u8`  | `fn u8p0_from_f32(x: f32) -> u8` / `fn u8p0_to_f32(r: u8) -> f32` | n/a (1 byte) |
| `U8P1`  | `u8`  | `enc(x)->u8 { (x*2.0).round() as u8 }` / `dec(r)->f32 { r as f32 / 2.0 }` | n/a |
| `U8P4`  | `u8`  | `enc: (x*16.0).round() as u8` / `dec: r as f32 / 16.0` | n/a |
| `U16P8` | `u16` | `enc: (x*256.0).round() as u16` / `dec: r as f32 / 256.0` | **big-endian** on the wire |
| `S32P16`| `i32` | `enc: (x*65536.0).round() as i32` / `dec: r as f32 / 65536.0` | **big-endian** on the wire |
| `F8_1_7`| `u8`  | branch on bit 7 — see §2 | n/a |
| `U10P0` | `u16` | `enc: (x.round() as u16) | 0x400` / `dec: r & 0x3FF` | **big-endian** on the wire |
| `U24P0` | `u32` (3 bytes) | read 3 bytes hi→low: `(b0<<16)|(b1<<8)|b2` | **big-endian** (MMR address) |
| `U24P16`| `u32` (3 bytes) | `b0 as f32 + b1 as f32/256.0 + b2 as f32/65536.0` | big-endian (ShotSample HeadTemp) |
| `U32P0` | `u32` | `from_be_bytes` for DE1 packets | **big-endian** in GATT packets; **little-endian** for MMR register *values* |

Encode signature sketch (one consistent shape):
```rust
trait FixedPoint: Sized {
    type Raw;
    fn encode(value: f32) -> Self::Raw;   // applies clamping per §2
    fn decode(raw: Self::Raw) -> f32;
}
```
Match the Tcl clamps exactly (e.g. `U8P4` clamps input to 16.0, `F8_1_7`
errors above 127 and limits, `convert_float_to_U8P0` clamps to 256).

**F8_1_7** deserves its own dedicated type — it is the one genuinely
non-linear encoding and the round-trip is lossy in the high regime
(`>= 12.75 s` resolves to whole seconds).

### 9.2 Endianness concerns — explicit list

- **All DE1 GATT packets (cuuid_01..12): big-endian.** Multi-byte fields
  (`SampleTime`, `GroupPressure`, `MixTemp`, `MaxVol`, `WriteKey`, …) read MSB
  first.
- **MMR `Address`: big-endian** 3 bytes (hi, mid, low).
- **MMR register *values* (`Data` words): little-endian** 32-bit. The app
  reads them with `spec_ReadFromMMR_int` (`littleeendian`) and writes them with
  `long_to_little_endian_hex`. This is the single most error-prone spot.
- **Firmware file header on disk: little-endian** (`parse_firmware_file_header`,
  `binary.tcl:1148`). Not a BLE concern but noted for completeness.
- **Scales differ:** Decent Scale weight short is **big-endian signed**; Skale II
  weight short is **little-endian signed**. Do not assume a global scale
  endianness.

### 9.3 Phase 0 spike priorities

For the Phase 0 spike, implement and verify these two read paths first — they
are the highest-frequency, highest-value packets and exercise the bulk of the
fixed-point decoders:

1. **ShotSample (`cuuid_0D` / `A00D`)** — the 19-byte telemetry packet (§3.1).
   Requires `U16` (big-endian), `U8P4`, `U16P8`, and the 3-byte `U24P16`
   HeadTemp recombination. Subscribe via `enable` and decode the stream.
2. **StateInfo (`cuuid_0E` / `A00E`)** — the 2-byte state packet (§4). Trivial
   to decode (two `u8` enums) and necessary to drive every state machine.

Together these let the spike show live pressure/flow/temperature plus correct
state transitions without needing any write path. Defer profile upload (§5),
MMR (§6), and calibration (§7) to later phases. The write path that should come
next is **RequestedState** (§7.1, single byte) since it is the minimum needed to
wake/sleep/start the machine.

### 9.4 Open questions for a human to resolve

- **RequestedState length:** confirm the real BLE write is exactly 1 byte
  (the Tcl simulator appends a second byte; the BLE write does not — but verify
  against firmware docs / a packet capture).
- **Calibration field signedness:** `DE1ReportedVal` is tagged `unsigned` but
  encoded signed — confirm both are signed 16.16.
- **MMR map completeness:** addresses above `0x803820` are inferred from
  setter/getter procs, not from an authoritative comment block. Cross-check
  against the DE1 firmware source.
- **Temperatures characteristic (`cuuid_0A`)** is named but never parsed in the
  Tcl — its packet layout is unknown from this source.
- **Old vs new ShotSample spec:** the app auto-detects via a missing
  `SteamTemp`. Decide whether the Rust client must support the legacy 1.0
  layout or can require current firmware.
- **`SteamSettings` byte bitfield** semantics are unclear — `binary.tcl:180`
  computes `0 & 0x80 & 0x40` (always 0). The meaning of bits 6/7 is not
  documented in this source.

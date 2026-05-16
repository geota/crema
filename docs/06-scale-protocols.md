# 06 — Coffee Scale BLE Protocol Reference

Complete BLE protocol reference for every coffee scale supported by the Decent
Espresso DE1 tablet app. The legacy Tcl app
(`/Users/adrianmaceiras/code/de1app/de1plus/`) is the de-facto spec; every claim
below is cited as `file:line` against that tree.

Files referenced:

- `bluetooth.tcl` — per-scale enable/tare/timer/command procs, weight-notification parsers, scan-result handling, notification dispatch.
- `binary.tcl` — Decent Scale packet `*_spec` procs and `parse_decent_scale_recv`.
- `machine.tcl` — scale service/characteristic UUID definitions (lines 73–110).
- `device_scale.tcl` — scale device abstraction, `tare` dispatch.
- `vars.tcl` — scale runtime variables (no UUIDs live here; UUIDs are all in `machine.tcl`).

## Tcl decoding notes (read first — load-bearing for endianness)

The Tcl `binary scan` format characters used in the parsers below carry the
endianness. From `binary.tcl:64-122` (the `::fields::2form` interpreter) and
standard Tcl semantics:

| Format char | Meaning | Endianness |
|---|---|---|
| `c` / `cu` | 8-bit int / unsigned 8-bit | n/a |
| `s` | 16-bit int | **little-endian** (low-to-high) |
| `su` | unsigned 16-bit int | **little-endian** |
| `S` / `Su` | 16-bit int / unsigned | **big-endian** (high-to-low) — `binary.tcl:90-94` |
| `i` | 32-bit int | **little-endian** |
| `I` | 32-bit int | **big-endian** |
| `a1`, `a6`, `a*` | byte string of N (or rest) | n/a |
| `H*` | hex string of whole buffer | n/a |

In `::fields::unpack` specs, a field type `Short` maps to `s`-family and
the spec passes the endian word `bigeendian` (sic — the literal string used in
`binary.tcl:1386` etc.), but the **actual format letter chosen is the
capital `S`** for `Short`, so Decent Scale `Short` fields are big-endian. The
`int` spec type maps to `I`/`i`. See "Decent Scale" section for the precise
consequence.

All scales ultimately call `::device::scale::process_weight_update <grams>`
with weight already converted to grams.

---

## 1. Decent Scale (`scale_type` = `decentscale`)

### UUIDs (`machine.tcl:77-80`)

| Role | UUID |
|---|---|
| Service | `0000FFF0-0000-1000-8000-00805F9B34FB` |
| Weight/status notify (read) | `0000FFF4-0000-1000-8000-00805F9B34FB` (`cuuid_decentscale_read`) |
| Command write | `000036F5-0000-1000-8000-00805F9B34FB` (`cuuid_decentscale_write`) |
| Writeback (extra notify) | `83CDC3D4-3BA2-13FC-CC5E-106C351A9352` (`cuuid_decentscale_writeback`) |

Note the unusually short 16-bit-derived write UUID `000036F5-…`; that is
verbatim from `machine.tcl:78`. The writeback characteristic is also parsed by
`parse_decent_scale_recv` (`bluetooth.tcl:2694-2697`).

### Scan identification (`bluetooth.tcl:2197-2202`)

Device-name prefix match — `[string first <prefix> $name] == 0`:

- `"Decent Scale"` → `decentscale`
- `"ButtsHaus Scale"` → also `decentscale` (rebranded clone)

### Weight notification packet

Notifications arrive on `cuuid_decentscale_read` and are decoded by
`parse_decent_scale_recv` (`binary.tcl:1381-1440`). Two packet lengths exist:

- **7 bytes** — v1.x firmware (`decent_scale_generic_read_spec`, `binary.tcl:299-310`).
- **10 bytes** — v1.2 firmware (`decent_scale_generic_read_spec_v12`, `binary.tcl:312-326`).

The 2nd byte is the `command` field. If `command == 0xCE` or `0xCA` the packet
is re-parsed as a weight frame (`binary.tcl:1395-1418`).

7-byte weight frame — `decent_scale_weight_read_spec2` (`binary.tcl:339-348`):

| Offset | Field | Type | Notes |
|---|---|---|---|
| 0 | model | u8 | always `0x03` |
| 1 | wtype | u8 | `0xCE` or `0xCA` |
| 2–3 | weight | **signed 16-bit big-endian** (`Short`) | raw |
| 4–5 | rate | unsigned 16-bit big-endian (`Short`) | flow rate, **unused** by app |
| 6 | xor | u8 | XOR checksum |

10-byte weight frame — `decent_scale_weight_read_spec_v12` (`binary.tcl:350-364`):

| Offset | Field | Type |
|---|---|---|
| 0 | model | u8 |
| 1 | wtype | u8 |
| 2–3 | weight | signed 16-bit big-endian |
| 4 | minutes | u8 |
| 5 | seconds | u8 |
| 6 | milliseconds | u8 |
| 7–8 | unused1, unused2 | u8 |
| 9 | xor | u8 |

**Weight formula** (`bluetooth.tcl:2751-2753`):
`grams = weight / 10.0` (weight is the signed big-endian short).

**Timestamp** (10-byte frames only, `binary.tcl:1410-1411`):
`timestamp = minutes*600 + seconds*10 + milliseconds` (units of 0.1 s),
stored in `de1(scale_timestamp)` (`bluetooth.tcl:2754-2755`).

Other `command` values handled on the read characteristic:

- `0xAA` → button-press event. `data3` = 1 (tare/"O" button) or 2 (timer/"[]" button) — `bluetooth.tcl:2716-2737`, `binary.tcl:1421-1423`.
- `0x0A` → LED/heartbeat callback. `data5` = battery level, `data6` = firmware version (`bluetooth.tcl:2738-2749`).
- `0x0F` + `data6 == 0xFE` → tare-success ACK (`bluetooth.tcl:2711-2715`).

### Command format

All command frames are 7 bytes built by `decent_scale_make_command`
(`bluetooth.tcl:1201-1222`):

```
byte0 = 0x03               (model/header)
byte1 = cmdtype
byte2 = cmddata1
byte3 = cmddata2  (0x00 if unused)
byte4 = cmddata3  (0x00 if unused)
byte5 = cmddata4  (0x00 if unused)
byte6 = XOR checksum
```

**XOR checksum** (`bluetooth.tcl:1177-1199`): XOR of all six preceding bytes:
`xor = 0x03 ^ cmdtype ^ cmddata1 ^ cmddata2 ^ cmddata3 ^ cmddata4`
(unused data bytes count as `0x00`).

| Action | Bytes (hex) | Source |
|---|---|---|
| LED on, grams | `03 0A 01 01 00 01 <xor>` | `bluetooth.tcl:1274` |
| LED on, ounces | `03 0A 01 01 01 01 <xor>` | `bluetooth.tcl:1277` |
| LED off | `03 0A 00 00 00 00 <xor>` | `bluetooth.tcl:1288` |
| Power off | `03 0A 02 00 00 00 <xor>` | `bluetooth.tcl:1289` |
| Heartbeat | `03 0A 03 FF FF 00 <xor>` | `bluetooth.tcl:949` |
| Timer start | `03 0B 03 00 00 00 <xor>` | `bluetooth.tcl:1323` |
| Timer stop | `03 0B 00 00 00 00 <xor>` | `bluetooth.tcl:1350` |
| Timer reset | `03 0B 02 00 00 00 <xor>` | `bluetooth.tcl:1363-1375` |
| Tare | `03 0F <ctr> 00 00 01 <xor>` | `bluetooth.tcl:1242` |

Tare uses an incrementing counter byte `<ctr>` (`decent_scale_tare_counter`,
init 253, wraps 255→0 — `bluetooth.tcl:1224-1244`). The 6th data byte is `01`.

### Quirks

- **Heartbeat required**: send `0A 03 FF FF` every 1000 ms (`bluetooth.tcl:937-980`); the 6th byte of the LED-on command being `0x01` means "require heartbeat" (`bluetooth.tcl:1271`).
- **Command duplication**: v1.0 firmware drops commands, so the app sends every timer/tare/LED command **twice** (`bluetooth.tcl:1331,1359,1385,1411`, and `after 500`/`after 1000` re-sends in the generic wrappers `bluetooth.tcl:24-25,56-57`).
- LED-disable is intentionally suppressed in normal use so the scale doesn't look "off" (`bluetooth.tcl:40-41`).

---

## 2. Skale II / Atomax (`scale_type` = `atomaxskale`)

### UUIDs (`machine.tcl:73-76`)

| Role | UUID |
|---|---|
| Service | `0000FF08-0000-1000-8000-00805F9B34FB` (`suuid_skale`) |
| Command write | `0000EF80-0000-1000-8000-00805F9B34FB` (`cuuid_skale_EF80`) |
| Weight notify | `0000EF81-0000-1000-8000-00805F9B34FB` (`cuuid_skale_EF81`) |
| Button notify | `0000EF82-0000-1000-8000-00805F9B34FB` (`cuuid_skale_EF82`) |

### Scan identification (`bluetooth.tcl:2190-2191`)

Name prefix `"Skale"` → `atomaxskale`.

### Weight notification packet (`cuuid_skale_EF81`)

Parsed inline in the dispatch handler (`bluetooth.tcl:2699-2703`):

```tcl
binary scan $value cus1cu t0 t1 t2 t3 t4 t5
set sensorweight [expr {$t1 / 10.0}]
```

| Offset | Field | Type | Notes |
|---|---|---|---|
| 0 | t0 | u8 | header / unused |
| 1–2 | t1 | **signed 16-bit little-endian** (`s`) | weight, raw |
| 3 | t2 | s8 (`s1`) | unused |
| 4 | t3 / t4 | u8 | unused |
| … | t5 | u8 | unused |

The format string `cus1cu` is: `c`=skip 1 byte (t0), `u` modifies it →
`cu` reads one unsigned byte, `s1` reads one signed 16-bit **little-endian**
short (t1), `cu` reads one unsigned byte. Note the count digit `1` after `s`.

**Weight formula** (`bluetooth.tcl:2702`): `grams = t1 / 10.0`.

Decent Scale–style flow/timestamp fields are **not** decoded for Skale.

### Button notification packet (`cuuid_skale_EF82`)

`bluetooth.tcl:2799-2814`: `binary scan $value cucucucucucu t0 t1`.
`t0 == 1` → tare button; `t0 == 2` → timer/espresso button.

### Command format (single-byte writes to `cuuid_skale_EF80`)

| Action | Byte | Source |
|---|---|---|
| Tare | `0x10` | `bluetooth.tcl:303` |
| Timer start | `0xDD` | `bluetooth.tcl:204` |
| Timer stop | `0xD1` | `bluetooth.tcl:274` |
| Timer reset | `0xD0` | `bluetooth.tcl:289` |
| Enable grams | `0x03` | `bluetooth.tcl:228` |
| Screen on | `0xED` | `bluetooth.tcl:242` |
| Display weight on LCD | `0xEC` | `bluetooth.tcl:243` |
| Screen off | `0xEE` | `bluetooth.tcl:259` |

No checksum. Each command is one raw byte.

### Quirks

- Atomax firmware always reports battery as 100 %, so the app hard-codes battery=100 and `scale_usb_powered=1` (`bluetooth.tcl:2348-2351`).
- Init sequence on connect: LCD on, then weight notifications (`+1 s`), button notifications (`+2 s`), LCD on again (`+3 s`) — `bluetooth.tcl:2353-2356`.

---

## 3. Felicita Arc (`scale_type` = `felicita`)

### UUIDs (`machine.tcl:86-87`)

| Role | UUID |
|---|---|
| Service | `0000FFE0-0000-1000-8000-00805F9B34FB` (`suuid_felicita`) |
| Weight notify + command write | `0000FFE1-0000-1000-8000-00805F9B34FB` (`cuuid_felicita`) |

Single characteristic used for both notify and write.

### Scan identification (`bluetooth.tcl:2203-2204`)

Name prefix `"FELICITA"` → `felicita`.

### Weight notification packet (`felicita_parse_response`, `bluetooth.tcl:409-435`)

Requires ≥ 9 bytes. `binary scan $value cucua1a6 h1 h2 sign weight`:

| Offset | Field | Type | Notes |
|---|---|---|---|
| 0 | h1 | u8 | must be `1` |
| 1 | h2 | u8 | must be `2` |
| 2 | sign | 1-byte ASCII char | `"-"` ⇒ negative |
| 3–8 | weight | **6-byte ASCII decimal string** | parsed with `scan %d` |

The weight is **not** a binary integer — it is 6 ASCII digit characters. The
parser does `scan $weight %d` to turn the digit string into an integer
(`bluetooth.tcl:413`).

**Weight formula** (`bluetooth.tcl:418`): `grams = weight / 100.0`
(negate if `sign == "-"`).

**Battery** (`bluetooth.tcl:422-434`): if packet ≥ 18 bytes,
`binary scan $value c15cu unused battery` — battery byte at offset 15.
`batt_level = round((battery - 129) / 29.0 * 10) * 10` (clamped to 10 % steps).

### Command format (single-byte writes to `cuuid_felicita`)

| Action | Byte (ASCII) | Source |
|---|---|---|
| Tare | `0x54` (`'T'`) | `bluetooth.tcl:357` |
| Timer start | `0x52` (`'R'`) | `bluetooth.tcl:389` |
| Timer stop | `0x53` (`'S'`) | `bluetooth.tcl:404` |
| Timer reset | `0x43` (`'C'`) | `bluetooth.tcl:374` |

No checksum, no heartbeat.

---

## 4. Bookoo (`scale_type` = `bookoo`)

### UUIDs (`machine.tcl:88-90`)

| Role | UUID |
|---|---|
| Service | `00000FFE-0000-1000-8000-00805F9B34FB` (`suuid_bookoo`) |
| Weight notify | `0000FF11-0000-1000-8000-00805F9B34FB` (`cuuid_bookoo`) |
| Command write | `0000FF12-0000-1000-8000-00805F9B34FB` (`cuuid_bookoo_cmd`) |

### Scan identification (`bluetooth.tcl:2215-2216`)

Name prefix `"BOOKOO_SC"` → `bookoo`.

### Weight notification packet (`bookoo_parse_response`, `bluetooth.tcl:520-541`)

Requires ≥ 9 bytes.
`binary scan $value cucucucucucua1cucucu h1 h2 h3 h4 h5 h6 sign w1 w2 w3`:

| Offset | Field | Type | Notes |
|---|---|---|---|
| 0–5 | h1..h6 | u8 ×6 | header / metadata, not validated |
| 6 | sign | 1-byte ASCII char | `"-"` ⇒ negative |
| 7 | w1 | u8 | weight byte (MSB) |
| 8 | w2 | u8 | weight byte (mid) |
| 9 | w3 | u8 | weight byte (LSB) |

**Weight formula** (`bluetooth.tcl:529-534`):
`weight = ((w1 << 16) | (w2 << 8) | w3) / 100.0` — **big-endian** 24-bit
unsigned. Negate if `sign == "-"`. Result rounded to 2 decimals.

### Command format (writes to `cuuid_bookoo_cmd`)

Fixed 6-byte payloads (`bluetooth.tcl:454-518`):

| Action | Bytes (hex) | Source |
|---|---|---|
| Tare | `03 0A 01 00 00 08` | `bluetooth.tcl:465` |
| Timer start | `03 0A 04 00 00 0A` | `bluetooth.tcl:498` |
| Timer stop | `03 0A 05 00 00 0D` | `bluetooth.tcl:514` |
| Timer reset | `03 0A 06 00 00 0C` | `bluetooth.tcl:482` |

The 6th byte appears to be a checksum baked into the constant; the Tcl never
computes it (commands are hard-coded `binary decode hex` literals). **Treat
these as opaque fixed byte strings when reimplementing.**

No heartbeat.

---

## 5. Acaia gen1 / IPS (`scale_type` = `acaiascale`)

Acaia uses a proprietary **framed protocol** with a stateful receive buffer.
This is the most complex scale in the app.

### UUIDs (`machine.tcl:81-82`)

| Role | UUID |
|---|---|
| Service | `00001820-0000-1000-8000-00805F9B34FB` (`suuid_acaia_ips`) |
| Weight notify + command write | `00002A80-0000-1000-8000-00805F9B34FB` (`cuuid_acaia_ips_age`) |

Both notify and write use the single `2A80` characteristic.

### Scan identification (`bluetooth.tcl:2221-2224`)

Name prefix `"ACAIA"` **or** `"PROCH"` → `acaiascale`.
If the name contains `"PROCH"`, `force_acaia_heartbeat` is set to 1
(`bluetooth.tcl:2386-2388`).

### Frame structure (`acaia_encode`, `bluetooth.tcl:909-918`; `acaia_scan_buffer_for_msg`, `1062-1111`)

```
byte0 = 0xEF   HEADER1
byte1 = 0xDD   HEADER2
byte2 = msg_type
byte3 = length          (incoming frames; payload length, includes part of metadata)
byte4 = event_type      (incoming frames)
byte5.. = payload
last 2 bytes = checksum  (incoming)
```

`ACAIA_METADATA_LEN = 5` (`bluetooth.tcl:898`). Minimum message length = 6
(`acaia_msg_at_minimum`, `bluetooth.tcl:1053-1056`).

### Receive parsing — stateful (`acaia_parse_response`, `bluetooth.tcl:1113-1139`)

Notifications are appended to a **global buffer** `acaia_command_buffer`
(decoded with `cu*` to a byte list). The buffer is scanned for the
`EF DD` header pair. Once a full frame is buffered, weight is decoded; the
buffer and `acaia_msg_start`/`acaia_msg_end` are reset.

Only `msg_type == 12` with `event_type == 5` or `11` and `length <= 64` are
treated as weight messages (`bluetooth.tcl:1097`).

**Payload offset** (`acaia_find_payload`, `bluetooth.tcl:1141-1152`):
- `event_type == 5` → `payload_offset = msg_start + 5`
- `event_type == 11` → `payload_offset = msg_start + 5 + 3`

**Weight decode** (`acaia_decode_weight`, `bluetooth.tcl:1154-1171`), payload
bytes indexed from `payload_offset`:

| Payload index | Field |
|---|---|
| 0 | weight byte 0 (LSB) |
| 1 | weight byte 1 |
| 2 | weight byte 2 (MSB) |
| 4 | `unit` (decimal-places exponent) |
| 5 | sign flag (`> 1.0` ⇒ negative) |

**Weight formula**:
`value = (b2 << 16) + (b1 << 8) + b0` (little-endian 24-bit unsigned),
`grams = value / 10^unit`. Negate if payload\[5\] > 1.

Note: weight bytes are **little-endian** here, unlike Bookoo/Varia.

### Commands

Tare (`acaia_tare`, `bluetooth.tcl:920-935`): `acaia_encode 04` with a 17-byte
zero payload → `EF DD 04 00…(17 bytes of 00)`. `write_type` = 1 (no-response)
for gen1.

Connect handshake (`bluetooth.tcl:2381-2391`):
1. `+100 ms` enable weight notifications.
2. `+500 ms` `acaia_send_ident`.

- **Ident** (`acaia_send_ident`, `bluetooth.tcl:1002-1022`): `acaia_encode 0B 3031323334353637383930313233349A6D`. Resent every 400 ms until notifications start; then `acaia_send_config` and `acaia_send_heartbeat` are scheduled.
- **Config** (`acaia_send_config`, `bluetooth.tcl:1024-1037`): `acaia_encode 0C 0900010102020103041106`.
- **Heartbeat** (`acaia_send_heartbeat`, `bluetooth.tcl:983-1000`): `acaia_encode 00 02000200`. If `force_acaia_heartbeat == 1`, config + heartbeat repeat every ~1–2 s.

### Quirks

- The `acaia_encode` comment says checksum is "hardcofig"ed (hard-coded), not computed (`bluetooth.tcl:915`). The ident/config/heartbeat payloads include their checksum bytes baked into the literal. **Treat outbound payloads as opaque constants.**
- Stateful reassembly across notifications is mandatory — a single weight frame can span multiple BLE notifications.

---

## 6. Acaia Pyxis (`scale_type` = `acaiapyxis`)

Same framed protocol as Acaia gen1; different service/characteristics, and
notify and command are **separate** characteristics.

### UUIDs (`machine.tcl:83-85`)

| Role | UUID |
|---|---|
| Service | `49535343-FE7D-4AE5-8FA9-9FAFD205E455` (`suuid_acaia_pyxis`) |
| Status/weight notify | `49535343-1E4D-4BD9-BA61-23C647249616` (`cuuid_acaia_pyxis_status`) |
| Command write | `49535343-8841-43F4-A8D4-ECBE34729BB3` (`cuuid_acaia_pyxis_cmd`) |

### Scan identification (`bluetooth.tcl:2225-2229`)

Name prefix `"PEARLS"`, `"PEARL-"`, `"LUNAR"`, **or** `"PYXIS"` → `acaiapyxis`.

### Protocol

Frame structure, receive parsing, and weight decode are **identical** to Acaia
gen1 — the same `acaia_parse_response` / `acaia_decode_weight` are used,
dispatched from `cuuid_acaia_pyxis_status` (`bluetooth.tcl:2770-2772`).

Tare: `acaia_tare $suuid_acaia_pyxis $cuuid_acaia_pyxis_cmd 2` — `write_type`
= 2 (write-with-response), and tare is written to the **command** characteristic
(`device_scale.tcl:352`).

Connect handshake (`bluetooth.tcl:2392-2409`):
1. `force_acaia_heartbeat = 1` (always, for Pyxis).
2. MTU is set to 247 (`ble mtu $handle 247`).
3. `+500 ms` enable weight notifications on `cuuid_acaia_pyxis_status`.
4. `+1000 ms` `acaia_send_ident` on `cuuid_acaia_pyxis_cmd`, `write_type` = 2.

### Quirks

- `scale_enable_weight_notifications` has the Pyxis branch **commented out** (`bluetooth.tcl:145-146`); enabling notifications happens only in the connect handler. Not a bug for runtime, but note it: the generic path does nothing for Pyxis.
- Heartbeat is always forced on for Pyxis.

---

## 7. Atomheart Eclair (`scale_type` = `atomheart_eclair`)

### UUIDs (`machine.tcl:91-93`)

| Role | UUID |
|---|---|
| Service | `B905EAEA-2E63-0E04-7582-7913F10D8F81` (`suuid_atomheart_eclair`) |
| Weight notify | `AD736C5F-BBC9-1F96-D304-CB5D5F41E160` (`cuuid_atomheart_eclair`) |
| Command write | `4F9A45BA-8E1B-4E07-E157-0814D393B968` (`cuuid_atomheart_eclair_cmd`) |

### Scan identification (`bluetooth.tcl:2218-2219`)

Name prefix `"ECLAIR"` → `atomheart_eclair`.

### Weight notification packet (`atomheart_eclair_parse_response`, `bluetooth.tcl:641-663`)

Requires ≥ 9 bytes. `binary scan $value ciIc h1 weight_value timer_value xor_byte`:

| Offset | Field | Type | Notes |
|---|---|---|---|
| 0 | h1 | s8 | must be `'W'` = `0x57` |
| 1–4 | weight_value | **signed 32-bit little-endian** (`i`) | milligrams |
| 5–8 | timer_value | **signed 32-bit big-endian** (`I`) | timer |
| 9 | xor_byte | s8 | XOR checksum |

Note the mixed endianness in the format string `ciIc`: lowercase `i` =
little-endian 32-bit (weight), uppercase `I` = big-endian 32-bit (timer).

**Weight formula** (`bluetooth.tcl:651`): `grams = weight_value / 1000.0`
(value is in milligrams, signed). Rounded to 2 decimals.

**XOR validation** (`atomheart_eclair_validate_xor`, `bluetooth.tcl:623-639`):
XOR of all payload bytes except byte 0 (header) and the last byte; result must
equal the last byte. A failed XOR raises an error (`bluetooth.tcl:659`).

### Command format (writes to `cuuid_atomheart_eclair_cmd`)

| Action | Bytes (hex) | Source |
|---|---|---|
| Tare | `54 01 01` | `bluetooth.tcl:568` |
| Timer reset | `54 01 01` (same as tare) | `bluetooth.tcl:585` |
| Timer start | `43 01 01` | `bluetooth.tcl:601` |
| Timer stop | `43 00 00` | `bluetooth.tcl:617` |

No separate timer-reset command — the scale resets the timer on tare
(`bluetooth.tcl:584`). No heartbeat.

---

## 8. Eureka Precisa (`scale_type` = `eureka_precisa`)

Also covers Krell CFS-9002 (`bluetooth.tcl:713`).

### UUIDs (`machine.tcl:97-99`)

| Role | UUID |
|---|---|
| Service | `0000FFF0-0000-1000-8000-00805F9B34FB` (`suuid_eureka_precisa`) |
| Status/weight notify | `0000FFF1-0000-1000-8000-00805F9B34FB` (`cuuid_eureka_precisa_status`) |
| Command write | `0000FFF2-0000-1000-8000-00805F9B34FB` (`cuuid_eureka_precisa_cmd`) |

Shares the generic FFF0/FFF1/FFF2 service — disambiguated by name on scan,
**and** the notification dispatch is additionally guarded by
`$::settings(scale_type) == "eureka_precisa"` (`bluetooth.tcl:2787`).

### Scan identification (`bluetooth.tcl:2209-2210`)

Name prefix `"CFS-9002"` → `eureka_precisa`.

### Weight notification packet (`eureka_precisa_parse_response`, `bluetooth.tcl:791-802`)

Requires ≥ 9 bytes.
`binary scan $value "cucucu cu su cu su" h1 h2 h3 timer_running timer sign weight`:

| Offset | Field | Type | Notes |
|---|---|---|---|
| 0 | h1 | u8 | must be `170` (`0xAA`) — header |
| 1 | h2 | u8 | must be `9` — type |
| 2 | h3 | u8 | must be `65` (`0x41`) — notification type |
| 3 | timer_running | u8 | timer state flag |
| 4–5 | timer | **unsigned 16-bit little-endian** (`su`) | timer value |
| 6 | sign | u8 | `1` ⇒ negative |
| 7–8 | weight | **unsigned 16-bit little-endian** (`su`) | raw |

**Weight formula** (`bluetooth.tcl:799`): `grams = weight / 10.0`
(negate if `sign == 1`).

### Command format (writes to `cuuid_eureka_precisa_cmd`)

All commands are 4-byte `AA <len> <code> <code>` frames (`bluetooth.tcl:742-789`):

| Action | Bytes (hex) | Source |
|---|---|---|
| Tare | `AA 02 31 31` | `bluetooth.tcl:744` |
| Turn off | `AA 02 32 32` | `bluetooth.tcl:752` |
| Timer start | `AA 02 33 33` | `bluetooth.tcl:758` |
| Timer stop | `AA 02 34 34` | `bluetooth.tcl:764` |
| Timer reset (also stops) | `AA 02 35 35` | `bluetooth.tcl:771` |
| Beep twice | `AA 02 37 37` | `bluetooth.tcl:777` |
| Set unit = grams | `AA 03 36 00` | `bluetooth.tcl:784` |
| Set unit = oz | `AA 03 36 01` | `bluetooth.tcl:785` (defined, unused) |
| Set unit = ml | `AA 03 36 02` | `bluetooth.tcl:786` (defined, unused) |

The 3rd and 4th bytes of the 2-byte-payload commands are identical (looks like
the command code is duplicated rather than a real checksum). The grams command
uses a different shape. No heartbeat.

---

## 9. Difluid Microbalance (`scale_type` = `difluid`)

### UUIDs (`machine.tcl:106-107`)

| Role | UUID |
|---|---|
| Service | `000000EE-0000-1000-8000-00805F9B34FB` (`suuid_difluid`) |
| Weight notify + command write | `0000AA01-0000-1000-8000-00805F9B34FB` (`cuuid_difluid`) |

Single characteristic for notify and write.

### Scan identification (`bluetooth.tcl:2232-2233`)

Name prefix `"Microbalance"` → `difluid`.

### Weight notification packet (`difluid_parse_response`, `bluetooth.tcl:1542-1554`)

Requires ≥ 19 bytes. The parser is **hex-string based**, not binary-field
based:

```tcl
binary scan $value H* data
set weight [scan [string range $data 10 17] %x]
```

`H*` produces a lowercase hex string of the whole packet (2 hex chars/byte).
`string range $data 10 17` selects hex characters 10–17, i.e. **bytes 5–8**
(4 bytes), interpreted as a hexadecimal number via `scan %x`.

**Weight formula** (`bluetooth.tcl:1547-1550`):
`grams = weight / 10.0`, applied only when `weight < 20000`.

> **Ambiguity / caution.** Because the parse is done on the hex *string*, the
> 4-byte value at bytes 5–8 is read in the natural left-to-right byte order of
> the packet — equivalent to a **big-endian** unsigned 32-bit read of bytes
> 5..8. There is **no sign handling** in this parser — negative weights are not
> decoded. The `< 20000` guard silently drops out-of-range values. When
> reimplementing in Rust, treat bytes 5–8 as big-endian u32, divide by 10, and
> note that the legacy app cannot show negative weight on Difluid. Verify
> against a real device before trusting the offset.

### Command format (writes to `cuuid_difluid`)

Frames are `DF DF <…>` with a trailing checksum byte (`bluetooth.tcl:1455-1540`):

| Action | Bytes (hex) | Source |
|---|---|---|
| Enable auto-notifications (init) | `DF DF 01 00 01 01 C1` | `bluetooth.tcl:1466` |
| Tare | `DF DF 03 02 01 01 C5` | `bluetooth.tcl:1481` |
| Timer start | `DF DF 03 02 01 00 C4` | `bluetooth.tcl:1495` |
| Timer stop | `DF DF 03 01 01 00 C3` | `bluetooth.tcl:1509` |
| Timer reset | `DF DF 03 02 01 00 C4` (same as start) | `bluetooth.tcl:1523` |
| Set unit = grams | `DF DF 01 04 01 00 C4` | `bluetooth.tcl:1537` |

The last byte appears to be a checksum (looks like sum of the preceding bytes
mod 256, but the Tcl never computes it). **Treat as opaque fixed strings.**

### Quirks

- **Init required**: on connect the app enables BLE notifications, then writes the `DF DF 01 00 01 01 C1` "enable auto notifications" command, then sends `difluid_set_to_grams` (`bluetooth.tcl:1455-1470`). Without the enable command the scale does not push weight.
- Software tare is supported (unlike Smartchef).

---

## 10. Smartchef (`scale_type` = `smartchef`)

### UUIDs (`machine.tcl:103-105`)

| Role | UUID |
|---|---|
| Service | `0000FFF0-0000-1000-8000-00805F9B34FB` (`suuid_smartchef`) |
| Status/weight notify | `0000FFF1-0000-1000-8000-00805F9B34FB` (`cuuid_smartchef_status`) |
| Command write | `0000FFF2-0000-1000-8000-00805F9B34FB` (`cuuid_smartchef_cmd`) |

Generic FFF0/FFF1/FFF2 service; notification dispatch guarded by
`scale_type == "smartchef"` (`bluetooth.tcl:2793`).

### Scan identification (`bluetooth.tcl:2230-2231`)

Name prefix `"smartchef"` (lowercase) → `smartchef`.

### Weight notification packet (`smartchef_parse_response`, `bluetooth.tcl:1445-1452`)

```tcl
binary scan $value cu* binary
set weight [expr {([lindex $binary 5] << 8) + [lindex $binary 6]}]
if {[lindex $binary 3] > 10} { set weight [expr $weight * -1] }
```

| Offset | Field | Notes |
|---|---|---|
| 3 | sign indicator | `> 10` ⇒ negative |
| 5 | weight byte (MSB) | |
| 6 | weight byte (LSB) | |

**Weight formula** (`bluetooth.tcl:1447-1451`):
`weight = (byte5 << 8) + byte6` (**big-endian** unsigned 16-bit),
negate if `byte3 > 10`, then `grams = weight / 10.0`.

### Command format

**No software tare.** `smartchef_tare` only shows a popup telling the user to
press the physical tare button; the actual write is commented out
(`bluetooth.tcl:1429-1443`). No timer, LED, or heartbeat commands exist.

> **Ambiguity.** Smartchef has no command implementation at all beyond enabling
> notifications. Tare must be physical. There is no timer support.

---

## 11. Solo Barista LSJ-001 (`scale_type` = `solo_barista`)

This is an LSJ-001 scale; the protocol is **byte-identical to Eureka Precisa**
(same `AA 09 41` notification frame, same `AA …` command frames). The DE1 app
keeps them as separate `scale_type`s because the scan name differs.

### UUIDs (`machine.tcl:100-102`)

| Role | UUID |
|---|---|
| Service | `0000FFF0-0000-1000-8000-00805F9B34FB` (`suuid_solo_barista`) |
| Status/weight notify | `0000FFF1-0000-1000-8000-00805F9B34FB` (`cuuid_solo_barista_status`) |
| Command write | `0000FFF2-0000-1000-8000-00805F9B34FB` (`cuuid_solo_barista_cmd`) |

Generic FFF0/FFF1/FFF2; dispatch guarded by `scale_type == "solo_barista"`
(`bluetooth.tcl:2790`).

### Scan identification (`bluetooth.tcl:2212-2213`)

Name prefix `"LSJ-001"` → `solo_barista`.

### Weight notification packet (`solo_barista_parse_response`, `bluetooth.tcl:883-894`)

Identical to Eureka Precisa:
`binary scan $value "cucucu cu su cu su" h1 h2 h3 timer_running timer sign weight`.

| Offset | Field | Type | Notes |
|---|---|---|---|
| 0 | h1 | u8 | must be `170` (`0xAA`) |
| 1 | h2 | u8 | must be `9` |
| 2 | h3 | u8 | must be `65` (`0x41`) |
| 3 | timer_running | u8 | timer state flag |
| 4–5 | timer | unsigned 16-bit little-endian | |
| 6 | sign | u8 | `1` ⇒ negative |
| 7–8 | weight | unsigned 16-bit little-endian | raw |

**Weight formula** (`bluetooth.tcl:891`): `grams = weight / 10.0`
(negate if `sign == 1`).

### Command format (writes to `cuuid_solo_barista_cmd`)

Identical 4-byte `AA …` frames as Eureka Precisa (`bluetooth.tcl:834-881`):

| Action | Bytes (hex) |
|---|---|
| Tare | `AA 02 31 31` |
| Turn off | `AA 02 32 32` |
| Timer start | `AA 02 33 33` |
| Timer stop | `AA 02 34 34` |
| Timer reset | `AA 02 35 35` |
| Beep twice | `AA 02 37 37` |
| Set unit = grams | `AA 03 36 00` |

No heartbeat. Timer commands are double-sent 500 ms apart (`bluetooth.tcl:67-69`).

---

## 12. Hiroia Jimmy (`scale_type` = `hiroiajimmy`)

### UUIDs (`machine.tcl:94-96`)

| Role | UUID |
|---|---|
| Service | `06C31822-8682-4744-9211-FEBC93E3BECE` (`suuid_hiroiajimmy`) |
| Status/weight notify | `06C31824-8682-4744-9211-FEBC93E3BECE` (`cuuid_hiroiajimmy_status`) |
| Command write | `06C31823-8682-4744-9211-FEBC93E3BECE` (`cuuid_hiroiajimmy_cmd`) |

### Scan identification (`bluetooth.tcl:2206-2207`)

Name prefix `"HIROIA JIMMY"` → `hiroiajimmy`.

### Weight notification packet (`hiroia_parse_response`, `bluetooth.tcl:697-711`)

Requires ≥ 7 bytes. One `0x00` byte is appended before parsing
(`append value [binary decode hex 00]`, `bluetooth.tcl:699`) so the trailing
32-bit field can be read from a 7-byte packet:

```tcl
binary scan $value cucucucui h1 h2 h3 h4 weight
```

| Offset | Field | Type | Notes |
|---|---|---|---|
| 0–3 | h1..h4 | u8 ×4 | header / metadata |
| 4–7 | weight | **signed 32-bit little-endian** (`i`) | with 0x00 padding byte |

**Weight formula** (`bluetooth.tcl:702-706`):
- If `weight >= 8388608` (`0x800000`): `weight = (0xFFFFFF - weight) * -1` — a 24-bit two's-complement sign correction.
- `grams = weight / 10.0`.

The sign correction implies the meaningful payload is a **24-bit** signed
value; the appended `0x00` byte makes the 4th byte zero so the `i` read yields
the 24-bit value in the low 3 bytes.

### Command format (writes to `cuuid_hiroiajimmy_cmd`)

| Action | Bytes (hex) | Source |
|---|---|---|
| Tare | `07 00` | `bluetooth.tcl:691` |

Only tare is implemented. No timer, LED, or heartbeat commands. The DE1 app
has no Hiroia timer support.

> **Ambiguity.** Hiroia Jimmy has only a tare command. The 4-byte header
> (h1..h4) is read but never validated, so the packet type is not checked
> before extracting weight — any notification on the status characteristic is
> treated as weight. Verify header semantics against a real device.

---

## 13. Varia Aku (`scale_type` = `varia_aku`)

Covers AKU Pro, AKU Mini, AKU Plus, AKU Micro (`bluetooth.tcl:1556`).

### UUIDs (`machine.tcl:108-110`)

| Role | UUID |
|---|---|
| Service | `0000FFF0-0000-1000-8000-00805F9B34FB` (`suuid_varia_aku`) |
| Status/weight notify | `0000FFF1-0000-1000-8000-00805F9B34FB` (`cuuid_varia_aku`) |
| Command write | `0000FFF2-0000-1000-8000-00805F9B34FB` (`cuuid_varia_aku_cmd`) |

Generic FFF0/FFF1/FFF2; dispatch guarded by `scale_type == "varia_aku"`
(`bluetooth.tcl:2767`).

### Scan identification (`bluetooth.tcl:2193-2195`)

Name prefix `"AKU MINI"` **or** `"Varia AKU"` → `varia_aku`.

### Notification packet (`varia_aku_parse_response`, `bluetooth.tcl:1585-1620`)

Requires ≥ 4 bytes.
`binary scan $value cucucua* header command length payload`:

| Offset | Field | Notes |
|---|---|---|
| 0 | header | not validated |
| 1 | command | `0x01` = weight, `0x85` = battery |
| 2 | length | payload length |
| 3.. | payload | command-dependent |

**Weight frame** — `command == 0x01 && length == 0x03`. Payload
`binary scan $payload cucucucu w1 w2 w3 xor`:

| Payload index | Field | Notes |
|---|---|---|
| 0 | w1 | weight MSB; **high nibble = sign** |
| 1 | w2 | weight mid |
| 2 | w3 | weight LSB |
| 3 | xor | checksum |

**Sign** (`bluetooth.tcl:1599`): `sign = w1 & 0x10`. If non-zero ⇒ negative.
The code comment notes Varia's docs say sign is the highest *bit*, but
empirically it is the high *nibble* — when the high nibble is `1`, negative.

**Weight formula** (`bluetooth.tcl:1603-1609`):
`weight = (((w1 & 0x0F) << 16) | (w2 << 8) | w3) / 100.0` — **big-endian**
24-bit unsigned, with the sign nibble masked off the top byte. Negate if
`sign > 0`. Rounded to 2 decimals.

**Battery frame** — `command == 0x85 && length == 0x01`:
`binary scan $payload cucu battery xor` — battery in payload byte 0.

> **Bug note (verbatim from source).** `bluetooth.tcl:1617` does
> `set ::de1(scale_battery_level) battery` — it stores the literal string
> `"battery"`, not `$battery`. The battery value is effectively never recorded.
> Do **not** copy this; in a Rust port store the actual battery byte.

### Command format (writes to `cuuid_varia_aku_cmd`)

| Action | Bytes (hex) | Source |
|---|---|---|
| Tare | `FA 82 01 01 82` | `bluetooth.tcl:1580` |

Only tare is implemented. The last byte (`0x82`) looks like an XOR/checksum
(`0x82 ^ 0x01 ^ 0x01 == 0x82`) but the Tcl never computes it — it is a
hard-coded literal. No timer or heartbeat.

---

## Rust mapping notes

### Endianness and grams formula per scale

| Scale | `scale_type` | Weight field endianness | Grams formula | Stateful framing? |
|---|---|---|---|---|
| Decent Scale | `decentscale` | **big-endian** signed i16 | `weight / 10.0` | No (but heartbeat req'd) |
| Skale II / Atomax | `atomaxskale` | **little-endian** signed i16 | `t1 / 10.0` | No |
| Felicita Arc | `felicita` | ASCII decimal string (6 chars) | `int(ascii) / 100.0` | No |
| Bookoo | `bookoo` | **big-endian** 24-bit unsigned | `val / 100.0` | No |
| Acaia gen1 / IPS | `acaiascale` | **little-endian** 24-bit unsigned | `val / 10^unit` | **Yes — framed buffer + heartbeat** |
| Acaia Pyxis | `acaiapyxis` | **little-endian** 24-bit unsigned | `val / 10^unit` | **Yes — framed buffer + heartbeat** |
| Atomheart Eclair | `atomheart_eclair` | **little-endian** signed i32 (mg) | `weight_mg / 1000.0` | No (per-frame XOR) |
| Eureka Precisa | `eureka_precisa` | **little-endian** unsigned u16 | `weight / 10.0` | No |
| Difluid Microbalance | `difluid` | **big-endian** u32 (bytes 5–8, via hex) | `weight / 10.0` | No (init cmd req'd) |
| Smartchef | `smartchef` | **big-endian** unsigned u16 | `weight / 10.0` | No |
| Solo Barista LSJ-001 | `solo_barista` | **little-endian** unsigned u16 | `weight / 10.0` | No |
| Hiroia Jimmy | `hiroiajimmy` | **little-endian** i32, 24-bit two's-complement corrected | `weight / 10.0` | No (0x00 byte appended pre-parse) |
| Varia Aku | `varia_aku` | **big-endian** 24-bit unsigned (sign in high nibble) | `val / 100.0` | No (per-frame XOR present, unchecked) |

### Grouping by similarity

**Group A — "simple scaled-integer weight" (notify char + command char, one
weight frame per notification, no reassembly).** Most scales. A Rust trait
`Scale { parse_weight(&[u8]) -> Option<f32>, tare_cmd() -> Vec<u8>, … }` covers
them directly:

- Decent Scale, Skale II, Bookoo, Atomheart Eclair, Eureka Precisa, Solo
  Barista, Hiroia Jimmy, Varia Aku, Smartchef, Difluid.
- Eureka Precisa and Solo Barista are byte-identical — implement once, share.

**Group B — outliers needing special handling:**

1. **Acaia gen1 + Acaia Pyxis — framed proprietary protocol.** Require:
   - A persistent receive buffer; a single weight frame may span multiple BLE
     notifications. Scan for the `EF DD` header, parse `msg_type`/`length`/
     `event_type`, wait until the full frame is buffered.
   - Connection handshake: ident → config → heartbeat. Heartbeat every ~1–2 s
     (always for Pyxis; for gen1 only when the device name contains `PROCH`).
   - Outbound payloads (ident/config/heartbeat/tare) have **baked-in checksums**
     — use them as opaque constants; the legacy app never computes them.
   - gen1 uses one characteristic for notify+write (`write_type` no-response);
     Pyxis uses separate notify/command characteristics (`write_type`
     write-with-response) and sets MTU 247.

2. **Decent Scale — checksum + heartbeat.** Commands need a computed XOR
   checksum (`0x03 ^ cmdtype ^ data1..data4`). Requires a 1 s heartbeat
   (`0A 03 FF FF`). Tare uses an incrementing counter byte. v1.0 firmware drops
   commands → send each command twice.

3. **Atomheart Eclair — per-frame XOR validation.** Incoming weight frames
   carry an XOR checksum over the payload; the app rejects frames that fail it.
   Mixed endianness within one frame (LE weight, BE timer).

4. **Felicita Arc — ASCII weight encoding.** Weight is six ASCII digit
   characters, not a binary integer; parse the string then divide by 100.

5. **Difluid Microbalance — init command + hex-string parse.** Must send
   `DF DF 01 00 01 01 C1` after connect or no weight is pushed. Legacy parser
   reads bytes 5–8 as a big-endian u32 via a hex string and has **no negative
   weight handling**.

6. **Smartchef — no software commands.** No tare/timer/LED writes exist; tare
   is physical-button only. The Rust model should expose `supports_tare =
   false`.

### Checksums summary

- **Computed**: Decent Scale (XOR over command bytes, `bluetooth.tcl:1177-1199`).
- **Validated on receive**: Atomheart Eclair (XOR over payload,
  `bluetooth.tcl:623-639`). Varia Aku weight frames carry an XOR byte but the
  app does **not** check it.
- **Baked into constants (never computed)**: Acaia ident/config/heartbeat,
  Bookoo command frames, Difluid command frames, Eureka/Solo command frames,
  Varia tare. Reimplement as fixed byte strings, or reverse-engineer and verify
  before computing.

### Known legacy bugs to NOT replicate

- Varia Aku battery is stored as the literal string `"battery"` instead of the
  parsed value (`bluetooth.tcl:1617`).
- Difluid parser silently drops any weight ≥ 20000 and cannot represent
  negative weight (`bluetooth.tcl:1547-1550`).
- Acaia Pyxis: the generic `scale_enable_weight_notifications` branch is
  commented out (`bluetooth.tcl:146`); only the connect handler enables
  notifications.

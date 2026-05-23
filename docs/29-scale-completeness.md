# 29 — Scale completeness matrix

**Status:** 2026-05-23 snapshot. Walked Crema's 12 scale codec files in
`core/de1-scale/src/`, every `proc <scale>_*` in
`de1app/de1plus/bluetooth.tcl`, and every scale implementation in
`reaprime/lib/src/models/device/impl/<scale>/`. The goal: are there other
"file exists but the write surface is partial" gaps like the Decent Scale
LCD/heartbeat surface that landed in PR-E?

The short answer is **no — the Decent Scale was the outlier**. The other
11 codec files are honest reflections of what each scale actually accepts
on the wire: every command legacy de1app implements is either present in
Crema, present and exposed through `Scale::{tare,timer}`, or
deliberately skipped (the Smartchef has no software tare; Hiroia / Varia
have no timer). The biggest *real* gap is no longer in the codec layer —
it is at the **app/UI wire-up** layer for the Acaia, Skale, and Decent
Scale, which all have richer codecs than the unified `Scale` enum
exposes via `tare()` / `timer()`. See §"Wire-up gaps" below.

## Summary

| Scale | Crema cmds | Legacy cmds | Reaprime cmds | % vs max | Tier-1 gap | Tier-2 gap |
|---|---|---|---|---|---|---|
| Acaia (gen1/Pyxis) | 4 (TARE, IDENT, CONFIG, HEARTBEAT) | 4 (same) | 4 (same) | 100% codec | — | Wire-up: only TARE reaches the shell; IDENT/CONFIG/HEARTBEAT are shell-side handshake. |
| Atomheart Eclair | 3 (TARE, TIMER_START, TIMER_STOP; reset = tare) | 3 (same) | 3 (same) | 100% | — | — |
| Bookoo | 11 (TARE, 3× timer, volume, standby, flow-smoothing, anti-mistouch, auto-stop, mode-toggle, 2× query) | 4 (tare + 3 timer) | 4 (tare + 3 timer) | 275% (Crema is ahead of both) | — | — |
| Decent Scale | 6 (TARE, 3× timer, LCD enable/disable, heartbeat, display-on-grams) | 6 (same + power-off) | 7 (same + power-off + battery decode) | ~85% (missing power-off) | — | Tier 2: explicit power-off byte 0x02 |
| Difluid Microbalance | 6 (TARE, 3× timer, set-grams, enable-notif) | 6 (same) | 6 (same) | 100% | — | — |
| Eureka Precisa | 7 (TARE, 3× timer, beep, set-grams, turn-off) | 7 (same) | 4 (TARE + 3× timer) | 100% vs legacy | — | — |
| Felicita Arc | 4 (TARE, 3× timer) | 4 (same) | 4 (same) | 100% | — | (no battery decode in core; reaprime decodes it) |
| Hiroia Jimmy | 1 (TARE only) | 1 (TARE only) | 2 (TARE + toggle-unit) | 50% vs reaprime | — | Tier 3: toggle display unit |
| Skale II | 9 (TARE, 3× timer, grams, display-on, display-weight, screen-on, screen-off) | 9 (same) | 9 (same) | 100% codec | — | Wire-up: grams/LCD constants exist but no `Scale::*` method exposes them |
| Smartchef | 0 software commands; weight-only | 0 (legacy explicitly comments "software tare not supported, press button") | 0 (same) | 100% | — | — |
| Solo Barista | re-exports Eureka Precisa (7 cmds) | 7 (same as Eureka) | 4 (TARE + 3× timer) | 100% | — | — |
| Varia Aku | 1 (TARE only) | 1 (same) | 1 (same) | 100% | — | — |

**Codec totals:** 51 distinct Crema commands across 12 scales, vs ~46
in legacy and ~50 in reaprime. Crema is **codec-complete** against
legacy for every scale except a single Decent Scale power-off byte. The
Bookoo codec is materially ahead of both other sources — most of the
"first-class scale" command surface (volume, standby, flow-smoothing,
anti-mistouch, auto-stop, mode toggle, settings query, serial query)
exists *only* in Crema.

## Per-scale detail

### Acaia (gen1 / Pyxis)

**Crema (`core/de1-scale/src/acaia.rs`):**

- `TARE: [u8; 20]` — `EF DD 04` + 17 zero bytes.
- `IDENT: [u8; 20]` — `EF DD 0B 30 31 32 …` (app identifier).
- `CONFIG: [u8; 14]` — `EF DD 0C 09 00 01 01 02 02 01 03 04 11 06`
  (selects notification subset).
- `HEARTBEAT: [u8; 7]` — `EF DD 00 02 00 02 00`.

These four constants exhaustively reproduce the legacy `acaia_tare`,
`acaia_send_ident`, `acaia_send_config`, `acaia_send_heartbeat`
payloads. The framing (`EF DD <type> <length> <event_type>` + payload)
matches both legacy and reaprime; `AcaiaDecoder` reproduces the
multi-notification reassembly the framed protocol requires.

**Legacy (`de1plus/bluetooth.tcl`):**

- `acaia_tare` @ 920 — bytes `EF DD 04` + 17 zeros.
- `acaia_send_heartbeat` @ 983 — bytes `EF DD 00 02 00 02 00`.
- `acaia_send_ident` @ 1002 — bytes `EF DD 0B 30 31 32 …`.
- `acaia_send_config` @ 1024 — bytes `EF DD 0C 09 00 01 01 02 02 01
  03 04 11 06`.

**Reaprime (`impl/acaia/acaia_scale.dart`):**

- `tare()` @ 295 — 3× send of `_encode(0x04, [0]*15)` with 100 ms
  spacing (the de1app/Decenza Pyxis-flakiness workaround).
- `_sendHeartbeat()` @ 266 — every 3 s; also re-sends config 1 s later.
- `_initScale` @ 208 — sends IDENT then CONFIG, up to 5 retries.
- `startTimer/stopTimer/resetTimer` @ 399–405 — all empty (Acaia has no
  software timer).

**Bytes check:** Crema's `TARE`, `IDENT`, `CONFIG`, `HEARTBEAT` all
match legacy bytes literally. Reaprime computes a two-byte
`(cksum1, cksum2)` over the payload; for the all-zero tare payload
both checksums are 0, so reaprime's emitted 20 bytes are identical to
Crema's and legacy's. For IDENT and CONFIG legacy hardcodes `9A 6D` /
`11 06` as the last two bytes; these are the same checksums reaprime
computes from the body, so the three implementations agree. **No
divergence.**

**Wire-up:** Only `TARE` reaches Crema's unified `Scale::tare()` path.
`IDENT` / `CONFIG` / `HEARTBEAT` are codec constants the shell uses
during connect / keepalive — not user-triggered writes, so they belong
in the Android transport layer, not in the unified Scale abstraction.
Crema's design exposes them as `pub const` for the shell to consume
directly. Reaprime drives the connect handshake from inside its
`onConnect`; Crema's split (codec sans-IO, shell owns scheduling) is
the correct architectural mirror of the rest of the BLE work.

**Gaps:** None at the codec layer. The retry-3× tare pattern reaprime
ships is a *shell-side* policy decision and is intentionally not in the
codec (Crema's `tare()` returns one packet; the shell can send it three
times if it wants).

**Tier:** N/A — complete.

### Atomheart Eclair

**Crema (`core/de1-scale/src/atomheart_eclair.rs`):**

- `TARE: [u8; 3]` = `54 01 01`.
- `TIMER_START: [u8; 3]` = `43 01 01`.
- `TIMER_STOP: [u8; 3]` = `43 00 00`.
- (TIMER_RESET maps to TARE in `Scale::timer`, matching the
  legacy/reaprime convention — the Eclair has no separate reset.)

**Legacy:**

- `atomheart_eclair_tare` @ 557 — `540101`.
- `atomheart_eclair_timer_reset` @ 574 — `540101` (same as tare).
- `atomheart_eclair_start_timer` @ 590 — `430101`.
- `atomheart_eclair_stop_timer` @ 606 — `430000`.

**Reaprime (`impl/atomheart/atomheart_scale.dart`):**

- `tare()` @ 98, `startTimer()` @ 118, `stopTimer()` @ 128, `resetTimer()`
  @ 138 → `tare()`. Same bytes.

**Bytes check:** Identical across all three. `parse_timer` was added in
the 2026-05-22 audit (docs/22 §5.3) to surface the scale's onboard
millisecond timer that reaprime decodes but legacy dropped on the
floor — this is decode side, not write side, but worth noting that
Atomheart parity now exceeds legacy.

**Gaps:** None. Codec complete.

**Tier:** N/A.

### Bookoo

**Crema (`core/de1-scale/src/bookoo.rs`):**

- `TARE`, `TIMER_START`, `TIMER_STOP`, `TIMER_RESET` — 6-byte commands.
- `set_volume(0..=5)` — beeper volume.
- `set_standby_minutes(5..=30)` — auto-standby timeout.
- `set_flow_smoothing(bool)` — toggle flow smoothing.
- `set_anti_mistouch(bool)` — toggle anti-mistouch.
- `set_auto_stop_mode(AutoStopMode)` — Flow-Stop vs Cup-Removal.
- `set_mode_enabled(BookooMode, bool)` and `select_mode(BookooMode)` —
  three-write sequence to switch active display mode.
- `QUERY_SERIAL` — reads serial / firmware / anti-mistouch state.
- `QUERY_SETTINGS` — reads active display mode + enabled-modes bitmask.
- Plus full 20-byte packet decode: weight, native flow, scale timer,
  battery %, standby minutes, beeper volume, flow-smoothing bit,
  auto-stop mode, checksum verification.

**Legacy:**

- `bookoo_tare` @ 454 — `030A01000008`.
- `bookoo_timer_reset` @ 471 — `030A0600000C`.
- `bookoo_start_timer` @ 487 — `030A0400000A`.
- `bookoo_stop_timer` @ 503 — `030A0500000D`.
- `bookoo_parse_response` @ 520 — decodes only weight bytes [7-9].

**Reaprime (`impl/bookoo/miniscale.dart`):**

- `tare()` @ 87, `startTimer()` @ 131, `stopTimer()` @ 140, `resetTimer()`
  @ 149 — same 6-byte sequences.

**Bytes check:** Crema's `TARE`, `TIMER_*` constants match legacy
exactly. Legacy `TIMER_RESET` is documented as `0C` but the captured
hex string is `030A0600000C`; that matches Crema's
`TIMER_RESET = command(0x06, 0, 0)` which xors to `0F`. Wait — that
is a **byte mismatch**. Let me re-check: command `0x06`, p1 `0x00`,
p2 `0x00`, xor = `0x03 ^ 0x0A ^ 0x06 ^ 0x00 ^ 0x00 = 0x0F`. Legacy
hardcodes `0C`. So **legacy has an incorrect hardcoded checksum on
timer_reset** — Crema's structurally-derived xor is correct (the test
`timer_constants_match_the_captured_bytes` pins this as `030a0600000f`).
This is exactly the bug class Crema's `const fn command()` was
designed to make impossible.

  Same recomputation for `timer_start`: `03 ^ 0A ^ 04 = 0D`. Legacy
  hardcodes `0A`. **Also wrong in legacy.** And `timer_stop`:
  `03 ^ 0A ^ 05 = 0C`. Legacy hardcodes `0D`. **Also wrong.**

  Only `tare` (`0x01 → xor 0x08`) is right in legacy.

  Implication: the legacy timer commands have been sending wrong
  checksums for the entire Bookoo lifetime in de1app. Either the
  Bookoo ignores the checksum byte, or the timer commands have
  silently never worked in legacy. Crema's bytes are the *correct*
  ones per the XOR scheme documented in the captured BOOKOO-app HCI
  trace.

**Gaps:** None — Crema is materially ahead. Bookoo has full first-class
support.

**Tier:** N/A; this is where every other scale is being pulled toward.

### Decent Scale

**Crema (`core/de1-scale/src/decent_scale.rs`):**

- `tare(counter) -> [u8; 7]` — `03 0F <counter> 00 00 01 <xor>`.
- `timer_start()`, `timer_stop()`, `timer_reset()`.
- `display_on_grams()` (legacy builder, equals `LCD_ENABLE_GRAMS`).
- `LCD_ENABLE_GRAMS: [u8; 7]` — `03 0A 01 01 00 01 08`.
- `LCD_DISABLE: [u8; 7]` — `03 0A 00 00 00 00 09`.
- `HEARTBEAT: [u8; 7]` — `03 0A 03 FF FF 00 0A`.
- `HEARTBEAT_INTERVAL_MS: u64 = 2_000`.

This is **the surface that landed in PR-E** and the one this audit was
spun out of. The tare counter (`DECENT_TARE_COUNTER_INIT = 253`,
wrapping) matches legacy `decent_scale_tare_cmd` exactly.

**Legacy:**

- `decentscale_tare` @ 1388 → `decent_scale_tare_cmd` @ 1239 — counter
  starts at 253.
- `decentscale_timer_start` @ 1310 — `decent_scale_make_command 0B 03 00`.
- `decentscale_timer_stop` @ 1335 — `0B 00 00`.
- `decentscale_timer_reset` @ 1363 — `0B 02 00`.
- `decentscale_enable_lcd` @ 1259 — `0A 01 01 00 01` (with byte-5 = 1
  meaning "require heartbeat"). Matches Crema's `LCD_ENABLE_GRAMS`.
- `decentscale_disable_lcd` @ 1283 — sends EITHER `0A 00 00` (Crema's
  `LCD_DISABLE`) when `keep_scale_on=1`, OR `0A 02` (power-off) when
  `keep_scale_on=0`. The **power-off byte 0x02 is the one Decent Scale
  cmd Crema does not currently encode**.
- `decentscale_send_heartbeat` @ 937 — `decent_scale_make_command 0A
  03 FF FF` (yielding `03 0A 03 FF FF 00 0A`). Matches Crema's
  `HEARTBEAT`. Legacy scheduling: 1 s cadence. Crema: 2 s.

**Reaprime (`impl/decent_scale/scale.dart`):**

- `tare()` @ 181 — `[0x03, 0x0F, 0x01, 0x00, 0x00, 0x01, 0x0C]`. Hardcoded
  counter `0x01`. Crema rolls 253→254→255→0…; reaprime is the simpler
  approach. Both are spec-legal; Crema matches legacy's rolling
  behaviour.
- `_sendHeartBeat` @ 190 — `[0x03, 0x0A, 0x03, 0xFF, 0xFF, 0x00, 0x0A]`,
  4 s cadence.
- `_sendOledOn` @ 257 — two writes: enable-LCD `[0x03, 0x0A, 0x01,
  0x00, 0x00, 0x01, 0x08]` and an extra `[0x03, 0x0A, 0x04, …]` that
  looks like a query/poll for battery%.
- `_sendOledOff` @ 273 — two writes including `[0x03, 0x0A, 0x04, 0x01,
  0x00, 0x01, 0x09]`.
- `_sendPowerOff` @ 298 — `[0x03, 0x0A, 0x02, 0x00, 0x00, 0x00, 0x00]`.
  Note the trailing `0x00` checksum — the correct xor would be `0x09`,
  matching `LCD_DISABLE`. Reaprime's hardcoded `0x00` may be a bug;
  legacy's `decent_scale_make_command 0A 02` would compute `0x09`
  through `decent_scale_make_command`. The Decent Scale either ignores
  the checksum here or reaprime has been quietly sending malformed
  power-off frames.

**Bytes check:** Crema's LCD enable / disable / heartbeat are
byte-identical to legacy. **Gap:** Crema has no explicit power-off
constant (legacy's `0A 02` variant of disable-LCD). The `LCD_DISABLE`
constant performs the milder "screen off, do not power off" path; the
"power off if `keep_scale_on=0`" branch has no Crema equivalent today.
There is also no `0x04 …` battery-query / battery-poll constant that
reaprime issues alongside enable/disable.

**Gaps:**

- ⚠️ Tier 2: no explicit `POWER_OFF` constant for the
  `keep_scale_on=0` shutdown path. Today the only way to power the
  Decent Scale off through Crema is to disconnect the BLE link.
- ⚠️ Tier 3: no battery-state-poll write (reaprime's `0x03, 0x0A,
  0x04, …` pattern).
- ✓ Wire-up: PR-E plumbed LCD enable/disable + heartbeat through the
  Android shell via `Scale::is_decent_scale` capability gate. Tare and
  timer route through the standard `Scale::tare`/`Scale::timer` paths.

**Tier:** Tier 2 — the user can run espresso shots; the missing
power-off is a small comfort gap, the battery poll is a diagnostic.

### Difluid Microbalance

**Crema (`core/de1-scale/src/difluid.rs`):**

- `ENABLE_NOTIFICATIONS: [u8; 7]` = `DF DF 01 00 01 01 C1`.
- `TARE: [u8; 7]` = `DF DF 03 02 01 01 C5`.
- `TIMER_START: [u8; 7]` = `DF DF 03 02 01 00 C4`.
- `TIMER_STOP: [u8; 7]` = `DF DF 03 01 01 00 C3`.
- `TIMER_RESET: [u8; 7]` = `TIMER_START` (same frame).
- `SET_UNIT_GRAMS: [u8; 7]` = `DF DF 01 04 01 00 C4`.

**Legacy:**

- `difluid_enable_weight_notifications` @ 1455 — `DFDF01000101C1`.
- `difluid_tare` @ 1472 — `DFDF03020101C5`.
- `difluid_start_timer` @ 1486 — `DFDF03020100C4`.
- `difluid_stop_timer` @ 1500 — `DFDF03010100C3`.
- `difluid_reset_timer` @ 1514 — `DFDF03020100C4` (same as start).
- `difluid_set_to_grams` @ 1528 — `DFDF01040100C4`.

**Reaprime (`impl/difluid/difluid_scale.dart`):** `tare()` @ 100,
`startTimer()` @ 186, `stopTimer()` @ 196, `resetTimer()` @ 213.

**Bytes check:** All six Crema constants are byte-identical to legacy.

**Gaps:** None. Codec complete. The module-level caution about the
weight-decode offset / unsigned-only reading is a *decode* concern,
not a write-surface concern.

**Tier:** N/A.

### Eureka Precisa

**Crema (`core/de1-scale/src/eureka_precisa.rs`):**

- `TARE: [u8; 4]` = `AA 02 31 31`.
- `TURN_OFF: [u8; 4]` = `AA 02 32 32`.
- `TIMER_START: [u8; 4]` = `AA 02 33 33`.
- `TIMER_STOP: [u8; 4]` = `AA 02 34 34`.
- `TIMER_RESET: [u8; 4]` = `AA 02 35 35`.
- `BEEP: [u8; 4]` = `AA 02 37 37`.
- `SET_UNIT_GRAMS: [u8; 4]` = `AA 03 36 00`.

**Legacy:**

- `eureka_precisa_tare` @ 742, `_turn_off` @ 750, `_start_timer` @ 757,
  `_stop_timer` @ 763, `_reset_timer` @ 769, `_beep_twice` @ 776,
  `_set_unit` @ 782. All seven payloads byte-identical to Crema.

**Reaprime (`impl/eureka/eureka_scale.dart`):** Only `tare()` @ 99,
`startTimer/stopTimer/resetTimer` @ 111–135. Reaprime does not surface
beep, turn-off, or set-unit.

**Bytes check:** All seven Crema constants match legacy. Crema covers
strictly more than reaprime.

**Gaps:** None at codec layer.

**Wire-up note:** `Scale::tare()` and `Scale::timer()` cover 4 of the 7
Crema constants. The `TURN_OFF`, `BEEP`, and `SET_UNIT_GRAMS` constants
have no method on the unified `Scale` abstraction — they are reachable
only as `de1_scale::eureka_precisa::TURN_OFF` etc. by callers willing
to bypass `Scale`. No UI invokes them today. This is the same shape as
the Skale and Acaia constants below: codec-rich, app-thin. Treat as a
deferred UI-surface decision rather than a codec defect.

**Tier:** N/A (codec). Tier 3 for the missing-UI items if anyone ever
wants Eureka turn-off / beep from the app.

### Felicita Arc

**Crema (`core/de1-scale/src/felicita.rs`):**

- `TARE = b'T'` (`0x54`).
- `TIMER_START = b'R'` (`0x52`).
- `TIMER_STOP = b'S'` (`0x53`).
- `TIMER_RESET = b'C'` (`0x43`).

**Legacy:**

- `felicita_tare` @ 346 — `54`.
- `felicita_start_timer` @ 378 — `52`.
- `felicita_stop_timer` @ 393 — `53`.
- `felicita_timer_reset` @ 363 — `43`.
- `felicita_parse_response` @ 409 also decodes a **battery percentage**
  from bytes 15+ when the frame is ≥18 bytes (so the scale reports its
  charge level).

**Reaprime (`impl/felicita/arc.dart`):** `tare()` @ 92, `startTimer()`
@ 149, `stopTimer()` @ 154, `resetTimer()` @ 159.

**Bytes check:** All four match legacy.

**Gaps:**

- ⚠️ Tier 3 (decode, not write): Crema does not parse the Felicita
  battery byte that legacy surfaces from notification bytes 15+. The
  battery decode lives on the *read* side, not the write side, so it
  is outside this audit's scope but worth recording. If a future
  "Felicita battery indicator" capability lands, the parse needs a 9 →
  18-byte path.

**Tier:** N/A for writes.

### Hiroia Jimmy

**Crema (`core/de1-scale/src/hiroia_jimmy.rs`):**

- `TARE: [u8; 2] = [0x07, 0x00]`. Only command.

**Legacy:**

- `hiroia_tare` @ 680 — `0700`.

**Reaprime (`impl/hiroia/hiroia_scale.dart`):**

- `tare()` @ 92.
- `_sendToggleUnit()` @ 120 — toggles the display unit. **Not in Crema
  or legacy.**

**Bytes check:** TARE matches across all three.

**Gaps:**

- ⚠️ Tier 3: reaprime has a `_sendToggleUnit()` private helper Crema
  does not encode. Hiroia users who want to switch the on-scale display
  between g / oz / ml can do it on-scale; the BLE command for it is a
  small comfort gap, not a workflow blocker.

**Tier:** Tier 3. Hiroia is rare; the missing command is a niche.

### Skale II (Atomax)

**Crema (`core/de1-scale/src/skale.rs`):**

- `CMD_ENABLE_GRAMS: u8 = 0x03`.
- `CMD_DISPLAY_WEIGHT: u8 = 0xEC`.
- `CMD_TARE: u8 = 0x10`.
- `CMD_TIMER_START: u8 = 0xDD`.
- `CMD_TIMER_RESET: u8 = 0xD0`.
- `CMD_TIMER_STOP: u8 = 0xD1`.
- `CMD_SCREEN_ON: u8 = 0xED`.
- `CMD_SCREEN_OFF: u8 = 0xEE`.

**Legacy:**

- `skale_tare` @ 299 — `10`.
- `skale_timer_start` @ 194 — `DD`.
- `skale_timer_stop` @ 269 — `D1`.
- `skale_timer_reset` @ 284 — `D0`.
- `skale_enable_grams` @ 224 — `03`.
- `skale_enable_lcd` @ 238 — sends `ED` then `EC` (screen-on then
  display-weight, sequenced).
- `skale_disable_lcd` @ 255 — `EE`.
- `skale_enable_button_notifications` @ 209 — notifications-enable,
  not a command write.

**Reaprime (`impl/skale/skale2_scale.dart`):**

- `tare()` @ 184, `startTimer()` @ 237, `stopTimer()` @ 247,
  `resetTimer()` @ 257. Plus `_sendDisplayOn` @ 154, `_sendDisplayWeight`
  @ 163, `_sendDisplayOff` @ 172.

**Bytes check:** Every legacy byte matches a Crema constant.

**Gaps:**

- ✓ Wire-up: `CMD_TARE` and `CMD_TIMER_*` route through the unified
  `Scale::tare`/`Scale::timer`. **`CMD_ENABLE_GRAMS`, `CMD_DISPLAY_WEIGHT`,
  `CMD_SCREEN_ON`, `CMD_SCREEN_OFF` are codec-only — they have no
  `Scale::*` method and no shell call site.** A Skale user today gets
  the scale's default display behaviour; the legacy "enable LCD with
  weight" sequence (`ED` then `EC`) does not run on Crema.

  This is structurally identical to the Decent Scale gap PR-E closed:
  the codec has the bytes, the unified abstraction does not surface
  them. The Skale is rarer than the Decent Scale (and is no longer
  sold), so it is a Tier 2/3 rather than Tier 1 case.

**Tier:** Tier 2 — Skale users see a less-polished default display than
they would on legacy or reaprime. Not workflow-blocking.

### Smartchef

**Crema (`core/de1-scale/src/smartchef.rs`):** Weight decode only. No
constants. `Scale::supports_tare()` returns `false` for Smartchef;
`Scale::tare()` returns `None`.

**Legacy:**

- `smartchef_tare` @ 1429 — *commented out*. Body says "software-based
  taring is not supported by the scale" and pops a popup telling the
  user to press the physical button.

**Reaprime (`impl/smartchef/smartchef_scale.dart`):**

- `tare()` @ 91 — also a no-op with comment "SmartChef doesn't
  support BLE tare command".

**Bytes check:** All three sources agree there is no BLE command surface.

**Gaps:** None.

**Tier:** N/A. Crema's behaviour is correct.

### Solo Barista (LSJ-001)

**Crema (`core/de1-scale/src/solo_barista.rs`):** One-line `pub use
crate::eureka_precisa::*` re-export. Inherits all seven Eureka Precisa
constants verbatim.

**Legacy:** Procs `solo_barista_*` @ 805–894 duplicate the Eureka
Precisa procs with the same payloads — legacy does not factor the
shared protocol; Crema does.

**Reaprime:** Subset (TARE + 3× timer) like Eureka.

**Bytes check:** All seven match legacy. The re-export pattern is
strictly tighter than legacy's copy-paste; the `solo_barista` advertised
name (`LSJ-001`) is the only thing the shell distinguishes.

**Gaps:** None.

**Tier:** N/A.

### Varia Aku

**Crema (`core/de1-scale/src/varia_aku.rs`):**

- `TARE: [u8; 5] = [0xFA, 0x82, 0x01, 0x01, 0x82]`.

**Legacy:**

- `varia_aku_tare` @ 1570 — `FA82010182`.

**Reaprime (`impl/varia/varia_aku_scale.dart`):**

- `tare()` @ 103. No timer (`startTimer/stopTimer/resetTimer` are empty).

**Bytes check:** TARE matches across all three.

**Gaps:** None. Varia Aku is BLE-tare-only by design (legacy and
reaprime confirm); Crema's `Scale::supports_timer()` correctly returns
`false`.

**Tier:** N/A.

## Pattern findings

1. **Crema's codec layer is honestly complete vs legacy.** Across 12
   scales there is exactly **one** missing write byte sequence — the
   Decent Scale power-off byte `0A 02` — and one missing decode field —
   the Felicita battery percentage. Every other write proc in
   `bluetooth.tcl` has a Crema counterpart.

2. **Crema is materially ahead of legacy on the Bookoo.** Six command
   IDs (volume, standby, flow-smoothing, anti-mistouch, auto-stop,
   mode-toggle) and two queries (serial, settings) only exist in Crema
   — legacy decodes only the weight bytes from the 20-byte Bookoo
   packet and has no setter for any of the Bookoo's configurable
   options. The first-class-scale capability surface in
   `Scale::capabilities()` is a Crema-only contribution.

3. **Crema fixed silent legacy bugs.** Legacy's Bookoo
   `timer_start` / `timer_stop` / `timer_reset` use **hand-typed XOR
   checksums that are wrong** for two of three (start and stop) and
   wrong by 3 for reset. Crema's `const fn command()` derives the xor
   structurally and is tested against a real-capture hex string in
   `timer_constants_match_the_captured_bytes`. Whether legacy's broken
   timer ever worked is unclear (the Bookoo may tolerate a bad
   checksum, or these have been quietly dropped on the wire). Either
   way, Crema is the only correct implementation.

4. **The "rich codec + thin Scale wire-up" pattern repeats on three
   scales.** Skale (`CMD_SCREEN_ON/OFF`, `CMD_ENABLE_GRAMS`,
   `CMD_DISPLAY_WEIGHT`), Eureka Precisa (`TURN_OFF`, `BEEP`,
   `SET_UNIT_GRAMS`), and Decent Scale (pre-PR-E: `LCD_ENABLE_GRAMS`,
   `LCD_DISABLE`, `HEARTBEAT`) all have codec constants the unified
   `Scale` enum does not expose via `tare()` / `timer()`. PR-E closed
   the Decent Scale case via `is_decent_scale()` capability + shell
   wire-up. The Skale + Eureka cases remain — but those scales have a
   smaller installed base, and the missing commands are display-cosmetic
   rather than workflow-blocking.

5. **No Tier-1 codec gaps remain.** Every scale Crema identifies can
   tare (where the scale supports it) and run a timer (where the scale
   has one). Every connected scale will weigh-in correctly. The
   "missing" cells in the matrix are all Tier 2 or Tier 3.

6. **The Decent Scale was genuinely the outlier.** PR-E added LCD +
   heartbeat because the **shell needed those reactive writes** to
   make the on-scale display work at all — not because the codec was
   missing more bytes than other scales. The other 11 scales either
   have no equivalent reactive-write surface, or that surface is
   already routed (Acaia handshake).

## Tier-1 recommendations

None. No codec change is required to unblock a common workflow on any
scale today.

## Tier-2 recommendations

1. **Skale display wire-up.** Add a Skale-only capability to
   `ScaleCapabilities` (or extend `is_decent_scale` into a small
   enum like `requires_display_init`) and have the shell send `ED EC`
   on connect and `EE` on disconnect/sleep, mirroring the PR-E Decent
   Scale pattern. Cost: ~50 lines. Justified if anyone surfaces
   "Skale display blank" feedback; defer otherwise.
2. **Decent Scale power-off.** Add `POWER_OFF: [u8; 7] = [0x03, 0x0A,
   0x02, 0x00, 0x00, 0x00, 0x09]` (xor recomputed correctly) and a
   shell hook to invoke it when the user explicitly powers down. The
   `LCD_DISABLE` constant Crema already has is the milder
   "screen-off-but-stay-connected" variant; power-off is the
   `keep_scale_on=0` legacy branch.
3. **Eureka Precisa beep / turn-off / set-unit.** Add three small
   convenience methods on `Scale` for completeness. Defer until anyone
   actually asks for them — the existing constants are reachable
   directly from `de1_scale::eureka_precisa::*` if a test or
   integration needs them.

## Tier-3 / deferred

1. **Felicita battery decode.** Read-side, not write-side; not in scope
   for this audit but worth flagging as an `18+`-byte path in the
   Felicita decoder.
2. **Hiroia toggle-unit.** Niche command on a rare scale; defer.
3. **Decent Scale battery poll** (the `0x03, 0x0A, 0x04, …` pattern
   reaprime issues alongside enable/disable). The Decent Scale's
   weight notifications already arrive in battery-percentage-tagged
   subtypes; a dedicated poll is a diagnostic at best.
4. **Acaia 3× tare retry.** Reaprime sends tare three times with 100 ms
   spacing as a Pyxis-specific reliability workaround. Crema's `tare()`
   returns one packet; the Android shell can implement the 3× repeat
   as a transport-layer retry policy without a codec change. Track as
   "shell-side scheduling decision" rather than a codec gap.

## Appendix — quick lookup

Codec source files: `core/de1-scale/src/{acaia,atomheart_eclair,
bookoo,decent_scale,difluid,eureka_precisa,felicita,hiroia_jimmy,
skale,smartchef,solo_barista,varia_aku}.rs`.

Unified abstraction: `core/de1-scale/src/scale.rs`
(`Scale::{tare,timer,capabilities,is_decent_scale,uuids,parse_reading}`).

Legacy procs: `de1app/de1plus/bluetooth.tcl` (proc names listed per
scale above).

Reaprime impls: `reaprime/lib/src/models/device/impl/<scale>/`.

App-level dispatch: `core/de1-app/src/lib.rs` —
`tare_scale` @ 1138, `set_scale_volume` @ 1355, `set_scale_standby` @
1378, `set_scale_flow_smoothing` @ 1399, `set_scale_anti_mistouch` @
1419, `set_scale_mode` @ 1445, `set_scale_auto_stop` @ 1470, plus
`push_timer_command` @ 1205. All non-tare/timer setters check
`scale.capabilities()` and emit nothing for a weight-only scale — the
right shape; no model-driven branching.

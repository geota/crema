# 30 — Scale handling: legacy de1app ↔ reaprime divergence audit

**Status:** 2026-05-23 snapshot. Walked every `proc <scale>_*` in
`de1app/de1plus/bluetooth.tcl`, every implementation file under
`reaprime/lib/src/models/device/impl/<scale>/`, the legacy scan/scale-type
detection block in `bluetooth.tcl:2030-2410`, and the legacy dispatch
layer in `de1plus/device_scale.tcl`. This doc complements docs/29 (which
counts codec coverage) by zooming in on the *behavioural* differences
between the two apps for the same scale.

The project rule for PR G: **when the two disagree, defer to reaprime
unless legacy is provably correct and reaprime is provably buggy**. The
audit identifies the exceptions to that rule explicitly.

## Summary

Across the 12 scales Crema supports, **8 show meaningful behavioural
divergence** between legacy and reaprime; 3 are essentially identical
once the dispatch layer is stripped away (Felicita, Hiroia, Varia Aku
write side); 1 is reaprime-only (BlackCoffee). The dominant pattern is
**legacy = scheduler-of-side-effects, reaprime = stateful object per
scale**:

- legacy uses `after N <proc>` from a global Tcl scheduler driven by
  `start_idle` / `start_sleep` events and a "double-send everything 500
  ms later" reliability heuristic;
- reaprime uses periodic Dart `Timer`s, retry loops at the `_initScale`
  level, and `_watchdogRetryAttempted` flags inside each scale object.

Top three places Crema should **defer to reaprime**:

1. **Reliability primitives.** Reaprime's watchdog-retry (re-subscribe
   after 3 missed ticks, disconnect after 5) is a strictly better model
   than legacy's blind "send twice" on Decent + Solo Barista. The Decent
   Scale double-send was a fix for a v1.0 firmware bug that v1.1
   resolved (legacy comment at `bluetooth.tcl:1330`); shipping it
   forever as a hard-coded `after 500` is the wrong layering.
2. **Acaia init-retry ladder.** Reaprime sends IDENT+CONFIG up to 10×
   waiting for `_receivingNotifications`, with protocol-specific
   notification-enable delay (IPS=100 ms, Pyxis=500 ms). Legacy retries
   IDENT itself unconditionally from `acaia_send_ident` with a fixed
   400 ms gap regardless of whether a notification was already seen.
3. **Decent Scale BLE watchdog cadence.** Reaprime's 4 s heartbeat / 12
   s warn / 20 s disconnect ladder is calibrated for BLE; legacy's 1 s
   heartbeat is excessive on BLE and has been observed driving
   keep-alive thrash (docs/19).

Top three places Crema should **ignore reaprime** (because reaprime is
buggy or legacy is correct):

1. **Decent Scale power-off checksum.** Reaprime sends
   `[0x03, 0x0A, 0x02, 0x00, 0x00, 0x00, 0x00]` — the trailing `0x00` is
   wrong; the XOR of `03 ^ 0A ^ 02 = 0x0B`. Legacy's
   `decent_scale_make_command 0A 02` would compute the correct byte.
   Crema's structurally-derived `POWER_OFF` (when added per docs/29 Tier
   2) must use the right XOR, not reaprime's hardcoded `0x00`.
2. **Bookoo timer checksums.** Legacy and reaprime both **hardcode the
   same wrong XOR bytes** (`030A0400000A` for start, `030A0500000D`
   for stop, `030A0600000C` for reset). Crema's `const fn command()`
   derives them as `0D`, `0C`, `0F` respectively. The scale either
   ignores the XOR or both apps have been silently shipping bad timer
   frames for years — but in either case, Crema's structurally-derived
   bytes are the source of truth. Do not "fix" Crema to match.
3. **Decent Scale tare counter.** Reaprime hardcodes counter byte
   `0x01` on every tare. Legacy rolls 253→254→…→0→1. The Decent Scale's
   firmware *requires* a not-recently-used counter to debounce repeat
   tares (legacy comment at `bluetooth.tcl:1226`); a constant `0x01`
   means the second tare in a row is silently dropped. Crema preserves
   the rolling counter — keep it.

Open questions for PR G are listed in §"Open questions" at the end.

## Per-scale divergence

### Acaia (gen1 / Pyxis)

**Capability detection.**
- *Legacy:* string-prefix match on advertised name at
  `bluetooth.tcl:2221-2229` — `ACAIA` / `PROCH` → `acaiascale` (IPS
  protocol); `PEARLS` / `PEARL-` / `LUNAR` / `PYXIS` → `acaiapyxis`
  (Pyxis protocol). The PROCH variant additionally sets
  `force_acaia_heartbeat=1` at `bluetooth.tcl:2386-2388`.
- *Reaprime:* `_serviceUuid` chosen at connect time by `discoverServices()`
  match — Pyxis service `49535343-fe7d-4ae5-8fa9-9fafd205e455` wins over
  IPS `1820` (acaia_scale.dart:125-136). Auto-detect by service UUID
  is structurally cleaner — name prefix can spoof, the service can't.
  **Reaprime is more robust.**

**Bytes (TARE).** Both encode `EF DD 04 <15 zero bytes>` followed by
two checksum bytes (over the body). Reaprime computes the checksums
arithmetically (acaia_scale.dart:186-204); legacy hardcodes them at
`bluetooth.tcl:930` as the literal payload (`0000000000000000000000000000000000`
which already includes the trailing zero checksums for an all-zero body).
**Bytes match.**

**Bytes (HEARTBEAT).** Legacy `bluetooth.tcl:989` =
`EF DD 00 02 00 02 00`. Reaprime `_sendHeartbeat()` @ 266 + `_encode`:
`EF DD 00 02 00 <cksum1=0x02> <cksum2=0x00>` — identical.

**Bytes (IDENT).** Legacy `bluetooth.tcl:1009` =
`EF DD 0B 30 31 32 33 34 35 36 37 38 39 30 31 32 33 34 9A 6D`.
Reaprime `_identPayload` = 15 ASCII bytes `30…34`, encoded via `_encode`
which computes the trailing checksums. For the legacy hardcoded
`9A 6D`: `9A = 0x30+0x32+0x34+0x36+0x38+0x30+0x32+0x34 = 0x19A & 0xFF =
0x9A`, matches reaprime's computed cksum1. Likewise `6D` matches cksum2.
**Bytes match — reaprime is the structurally-derived form.**

**Reliability — tare.** Reaprime sends tare **3×** at 100 ms spacing
inside `tare()` (acaia_scale.dart:295-317). Legacy sends tare **1×**.
Reaprime's 3× is the Decenza Pyxis workaround for occasional dropped
writes. **Defer to reaprime** for the Pyxis branch; the IPS branch
likely does not need it.

**Reliability — init.** Reaprime retries IDENT+CONFIG up to 10× with a
200 ms→500 ms pacing inside `_initScale` (acaia_scale.dart:218-243),
gated by `_receivingNotifications`. Legacy retries IDENT itself from
`acaia_send_ident` with a fixed 400 ms gap, then schedules a CONFIG
1000 ms later — but only if notifications haven't arrived; otherwise
it goes straight to heartbeat (bluetooth.tcl:1015-1021). **Both retry;
reaprime's is bounded; legacy's is unbounded.** Reaprime's bounded
retry is safer.

**Reliability — heartbeat.** Legacy fires every 2 s in PROCH-mode
(`after 1000 acaia_send_config ; after 2000 acaia_send_heartbeat`,
bluetooth.tcl:996-999); skipped entirely when
`force_acaia_heartbeat=0`. Reaprime fires every 3 s
(acaia_scale.dart:253), always, with a config-resend 1 s after each
heartbeat. **Reaprime is consistent; legacy is variant-dependent.**

**Watchdog.** Reaprime adds a 5 s response watchdog **only for Pyxis**
(acaia_scale.dart:258-263, `_checkWatchdog()`) that triggers a
disconnect if no notification arrives. Legacy has no such watchdog at
the codec layer (only the generic `device_scale.tcl::_watchdog_*` which
re-issues `scale_enable_weight_notifications`, not a disconnect).
**Reaprime is more aggressive — possibly too aggressive on flaky BLE.**

**Notification parsing.** Both implement the EF/DD framing scan with
`acaia_msg_at_minimum` + `acaia_msg_ready` style state machine.
Reaprime additionally captures `msgType == 8` battery level
(acaia_scale.dart:355-357); legacy does not. **Defer to reaprime** for
battery decode.

**Couplings.** Neither couples tare and timer (Acaia has no software
timer). Both implement `startTimer/stopTimer/resetTimer` as no-ops
(reaprime acaia_scale.dart:399-405; legacy has no scale-side acaia
timer proc).

**Notable bugs.** None on the write side. Legacy's hardcoded checksums
in IDENT (`9A 6D`) and CONFIG (`11 06`) are correct but brittle; one
typo and the scale would silently reject the frame.

---

### Atomheart Eclair

**Capability detection.**
- *Legacy:* name prefix `ECLAIR` → `atomheart_eclair`
  (bluetooth.tcl:2218).
- *Reaprime:* service UUID match. **Critical inconsistency:** reaprime
  declares the service UUID as `b905eaea-6c7e-4f73-b43d-2cdfcab29570`
  (atomheart_scale.dart:19) but legacy declares it as
  `B905EAEA-2E63-0E04-7582-7913F10D8F81` (machine.tcl:93). These are
  **different UUIDs**. One of the two is wrong. The reaprime form has
  the look of a synthetic placeholder (`6c7e-4f73-b43d-2cdfcab29570`
  is a continuation pattern); legacy's looks captured-from-the-wire.
  ⚠️ **PR G action: verify against a real Eclair on a sniffer.**

**Bytes.** TARE = `54 01 01`; TIMER_START = `43 01 01`; TIMER_STOP =
`43 00 00`; TIMER_RESET = same as TARE. All four bytes identical across
legacy (`bluetooth.tcl:557-621`) and reaprime
(`atomheart_scale.dart:98-141`).

**Reliability.** Neither double-sends. Reaprime uses `withResponse:
false`. Legacy uses `userdata_append … 0` (the trailing `0` is
"no-response"). Match.

**Lifecycle.** Legacy enables weight notifications 200 ms after connect
(`bluetooth.tcl:2380`). Reaprime calls `_registerNotifications()` on
connect synchronously after `discoverServices()` returns (no delay).
**Reaprime is faster, less guarded.**

**Notification parsing.** Both parse 10-byte frames starting with
`'W' (0x57)`, validate an XOR over bytes 1..end-1. Legacy decodes
weight only; **reaprime decodes both weight and the scale's onboard
timer field** (bytes 5-8, little-endian uint32, ms) — see
`atomheart_scale.dart:160-181`. The reaprime `timerValue` is then
surfaced on `ScaleSnapshot`. **Defer to reaprime** — legacy drops the
timer on the floor.

**Notable bugs.** The UUID mismatch above is the main worry.

---

### Bookoo

**Capability detection.**
- *Legacy:* name prefix `BOOKOO_SC` → `bookoo` (bluetooth.tcl:2215).
- *Reaprime:* service UUID `0ffe` match (miniscale.dart:13).

**Bytes (TARE).** Both = `03 0A 01 00 00 08`. **Match.**

**Bytes (TIMER_START).** Both = `03 0A 04 00 00 0A`. **Wrong checksum
in both.** Correct XOR: `03 ^ 0A ^ 04 = 0x0D`. Crema's
`const fn command()` produces `0D`. The Bookoo firmware appears to
ignore or tolerate the bad byte (or the timer has been quietly
non-functional in both apps for years). **Crema is correct; legacy and
reaprime agree on the *wrong* value.** Do not "fix" Crema down to
match.

**Bytes (TIMER_STOP).** Both = `03 0A 05 00 00 0D`. Correct XOR =
`0x0C`. **Same wrong-checksum pattern.**

**Bytes (TIMER_RESET).** Both = `03 0A 06 00 00 0C`. Correct XOR =
`0x0F`. **Same wrong-checksum pattern.**

**Reliability.** Neither double-sends. Neither has a watchdog at the
codec layer.

**Lifecycle.** Legacy `after 200 bookoo_enable_weight_notifications`
on connect (bluetooth.tcl:2365). Reaprime registers notifications
synchronously after service discovery.

**Notification parsing.** Both decode the 20-byte packet. Legacy
extracts only weight (`bluetooth.tcl:520-540`); **reaprime extracts
weight + battery from byte 13** (miniscale.dart:119-127). Crema
already does the full decode per docs/29 — Crema is ahead of both.

**Couplings.** Neither couples. Both treat tare/timer as orthogonal.

**Notable bugs.** Three wrong XOR checksums in both apps. The Bookoo
either accepts them or its timer is silently broken on both apps.
This is the docs/29 §Bookoo finding restated.

---

### Decent Scale

**Capability detection.**
- *Legacy:* name prefix `Decent Scale` OR `ButtsHaus Scale` (a
  third-party Decent-clone, bluetooth.tcl:2197-2202) → `decentscale`.
- *Reaprime:* service UUID `fff0` (scale.dart:15). But `fff0` is **also**
  used by Eureka Precisa, Solo Barista, Smartchef, Varia AKU — five
  different scales advertise the same service prefix. ⚠️ Reaprime
  relies on a *higher-level* registry (presumably name-prefix or
  user-selected) to disambiguate. **Crema must replicate the legacy
  name-prefix gate**, not rely on the `fff0` service alone.

**Bytes (TARE).** Legacy at `bluetooth.tcl:1242` =
`decent_scale_make_command 0F <counter> 00 00 01` where the counter
rolls 253→254→…→0. The 7th byte is the computed XOR. Reaprime at
`scale.dart:182` = hardcoded `[0x03, 0x0F, 0x01, 0x00, 0x00, 0x01, 0x0C]`.
The XOR checks out for counter=1 (`03^0F^01^01 = 0x0C`).

⚠️ **Bug in reaprime:** the counter is hardcoded to 1 on every tare,
but the Decent Scale firmware uses the counter to debounce identical
taps. Two taps in a row with the same counter byte will silently drop
the second. **Crema preserves legacy's rolling counter.** Do not adopt
reaprime here.

**Bytes (TIMER_START).** Legacy makes `0B 03 00` →
`03 0B 03 00 00 00 <XOR>`; XOR = `03^0B^03 = 0x0B`. Reaprime hardcodes
`[0x03, 0x0B, 0x03, 0x00, 0x00, 0x00, 0x0B]`. **Match.**

**Bytes (TIMER_STOP).** Legacy `0B 00 00` → `03 0B 00 00 00 00 <0x08>`.
Reaprime = `[0x03, 0x0B, 0x00, 0x00, 0x00, 0x00, 0x08]`. **Match.**

**Bytes (TIMER_RESET).** Legacy `0B 02 00` → `03 0B 02 00 00 00 <0x0A>`.
Reaprime = `[0x03, 0x0B, 0x02, 0x00, 0x00, 0x00, 0x0A]`. **Match.**

**Bytes (LCD enable).** Legacy at `bluetooth.tcl:1274-1277` sends
`0A 01 01 00 01` (with byte-5 = 1 meaning "require heartbeat", grams
mode) OR `0A 01 01 01 01` (oz mode) — built via
`decent_scale_make_command`. The XOR is `03^0A^01^01^00^01 = 0x08` for
the grams variant. Reaprime `_sendOledOn` issues
`[0x03, 0x0A, 0x01, 0x00, 0x00, 0x01, 0x08]` (matches legacy grams) **and
then** a second write `[0x03, 0x0A, 0x04, 0x00, 0x00, 0x01, 0x08]`
(scale.dart:265-270). The second write is **not in legacy** — it
appears to be a "request battery%" poll. The XOR `03^0A^04^00^00^01 =
0x0C`, **not the `0x08` reaprime hardcodes**. ⚠️ **Reaprime has a bad
checksum on this poll frame** (and it's not in legacy at all).

**Bytes (LCD disable / power-off).** Legacy `decentscale_disable_lcd`
at `bluetooth.tcl:1283-1302` sends EITHER `0A 00 00 ... <0x09>`
("screen off, stay connected" — fed by `keep_scale_on=1`) OR `0A 02` →
full `03 0A 02 00 00 00 <0x09>` ("power off" — `keep_scale_on=0`).
Reaprime `_sendOledOff` (scale.dart:273-287) sends two writes:
`[0x03, 0x0A, 0x04, 0x01, 0x00, 0x01, 0x09]` (XOR check:
`03^0A^04^01^00^01 = 0x0D`, **wrong** — should be `0x09` only if it
were `0x03^0A^00^01^00^01`, so the trailing byte here is wrong) and
`[0x03, 0x0A, 0x00, 0x01, 0x00, 0x01, 0x09]` (XOR check:
`03^0A^00^01^00^01 = 0x09`, **correct**). Reaprime
`_sendPowerOff` (scale.dart:298-307) sends
`[0x03, 0x0A, 0x02, 0x00, 0x00, 0x00, 0x00]` — XOR should be
`03^0A^02 = 0x0B`. ⚠️ **Reaprime's power-off checksum is wrong**
(also flagged in docs/29). Legacy would compute the correct byte
through `decent_scale_make_command`.

**Bytes (HEARTBEAT).** Legacy at `bluetooth.tcl:949` =
`decent_scale_make_command 0A 03 FF FF` → `03 0A 03 FF FF 00 <0x0A>`
(XOR: `03^0A^03^FF^FF = 0x0A`, correct). Reaprime
`_sendHeartBeat` at `scale.dart:194-197` ships the same bytes. **Match.**

**Reliability — double-send.** Legacy double-sends tare, timer start/
stop/reset, AND enable_lcd (`bluetooth.tcl:1331, 1359, 1385, 1411,
2335-2340`). Reaprime does NOT double-send any of these.
Legacy comment: "decent scale v1.0 occasionally drops commands, which
is being fixed in decent scale v1.1. So for now we send the same
command twice." That fix shipped years ago. **Reaprime's single-send
is correct for modern firmware.** Defer to reaprime.

**Reliability — heartbeat cadence.** Legacy = 1 s
(`after 1000 decentscale_send_heartbeat` at bluetooth.tcl:979).
Reaprime = 4 s with a watchdog at 12 s/20 s (scale.dart:96-130).
Legacy's 1 s burns BLE bandwidth; reaprime's 4 s is calibrated.
**Defer to reaprime.**

**Reliability — watchdog.** Reaprime tracks `_ticksSinceLastNotification`,
re-subscribes after 3 missed ticks, disconnects after 5 (scale.dart:34-36,
112-127). Legacy has no equivalent at the per-scale level. **Defer to
reaprime.**

**Lifecycle.** Legacy fires a complex `after`-scheduled sequence on
connect: heartbeat-now, heartbeat+2 s, LCD-on@200 ms, notif-enable@300
ms+@400 ms, LCD-on-redundant@500 ms (bluetooth.tcl:2331-2340).
Reaprime: subscribes, sends LCD-on, sends heartbeat, starts 4 s timer,
done (scale.dart:90-133). **Reaprime is simpler and equally correct.**

**Notification parsing.** Both branch on byte[1]: `0xCE`/`0xCA` →
weight, `0x0A` → battery-from-heartbeat-response. Both decode bytes
2-3 as big-endian int16, divide by 10. **Match.**

**Couplings.** Reaprime ties power-off to `disconnect()`
(scale.dart:166-178); legacy ties it to `keep_scale_on=0` setting. Both
make sense; reaprime's "always power off on disconnect" is more
deterministic, legacy's is user-configurable. Probably a wash.

**Notable bugs.**
- Reaprime tare counter hardcoded `0x01` — duplicate-tap debounce bug.
- Reaprime LCD-on second write (`0x04 …`) uses wrong XOR `0x08` (would
  be `0x0C` if computed).
- Reaprime LCD-off first write (`0x04 0x01 …`) uses wrong XOR `0x09`
  (would be `0x0D` if computed).
- Reaprime power-off uses XOR `0x00` (would be `0x0B` if computed).
- Reaprime ships `_sendOledOff` writing two frames, one of which is
  also a no-op-like construct (`0x00 0x01 …`) that legacy never sends.

For Decent Scale specifically, **legacy's `decent_scale_make_command`
is structurally correct; reaprime's hardcoded payloads have at least
three wrong checksums**. Crema's implementation, via the same
structural approach as `decent_scale_make_command`, agrees with legacy
where legacy is right and corrects reaprime where reaprime is wrong.
This is the one scale where defer-to-reaprime would be a regression.

---

### Difluid Microbalance

**Capability detection.**
- *Legacy:* name prefix `Microbalance` → `difluid` (bluetooth.tcl:2232).
- *Reaprime:* service UUID `00ee` (difluid_scale.dart:13).

**Bytes.** ENABLE_NOTIFICATIONS, TARE, TIMER_START, TIMER_STOP,
TIMER_RESET, SET_UNIT_GRAMS — all six bytes-identical across legacy
(`bluetooth.tcl:1455-1540`) and reaprime
(`difluid_scale.dart:17-25, 178-183`).

**Reliability.** Neither double-sends. Neither has a watchdog.

**Lifecycle.** Both subscribe + send ENABLE_NOTIFICATIONS + send
SET_UNIT_GRAMS on connect. Reaprime adds an auto-correction: if a
weight notification arrives reporting non-gram unit (`data[17] != 0`),
re-send SET_UNIT_GRAMS (difluid_scale.dart:148-154). **Legacy does
not.** Defer to reaprime.

**Bytes (TIMER_STOP coupling).** Legacy at `bluetooth.tcl:96-99` couples
stop-timer to reset-timer in the dispatcher (sends BOTH stop then reset
on `scale_timer_stop`). Reaprime at `difluid_scale.dart:196-210` does
the same inside `stopTimer()` — stop then start (start = reset).
**Behaviour matches; layering differs.**

**Notification parsing.** Both filter on `data[3] == 0`, extract bytes
5-8 as signed int (legacy: hex-and-mask; reaprime: ByteData int32 BE),
divide by 10. Match.

**Notable bugs.** None observed.

---

### Eureka Precisa

**Capability detection.**
- *Legacy:* name prefix `CFS-9002` → `eureka_precisa` (bluetooth.tcl:2209).
- *Reaprime:* service UUID `fff0` (eureka_scale.dart:13) — **collides
  with Decent Scale, Solo Barista, Smartchef, Varia AKU**. Must
  disambiguate by name.

**Bytes.** TARE = `AA 02 31 31`; TIMER_START = `AA 02 33 33`; TIMER_STOP
= `AA 02 34 34`; TIMER_RESET = `AA 02 35 35`. **Match across both apps**
(`bluetooth.tcl:742-773`, `eureka_scale.dart:99-143`).

**Surface coverage.** Legacy implements turn-off (`AA 02 32 32`),
beep-twice (`AA 02 37 37`), and set-unit-grams (`AA 03 36 00`).
Reaprime does **not** implement these three. Crema has all seven per
docs/29.

**Reliability.** Neither double-sends. Both have onboard battery service
read; reaprime tries `0x180F/0x2A19` at `_readBattery()`
(eureka_scale.dart:162-171), legacy does not poll battery for this
scale (it does for Skale).

**Lifecycle.** Legacy: `after 200 eureka_precisa_enable_weight_notifications`.
Reaprime: registers synchronously, reads battery.

**Notification parsing.** Legacy decodes header validation `h1 == 170
&& h2 == 9 && h3 == 65`, sign byte, 16-bit weight (`bluetooth.tcl:791-802`).
Reaprime is **looser**: no header validation, just reads bytes 6-8 as
sign + 16-bit weight (`eureka_scale.dart:173-189`). Reaprime will
mis-decode noise-on-the-wire that legacy would reject. **Defer to
legacy** for the header validation.

**Notable bugs.** None at the byte level; reaprime's missing header
check is a robustness gap.

---

### Felicita Arc

**Capability detection.**
- *Legacy:* name prefix `FELICITA` (bluetooth.tcl:2203).
- *Reaprime:* service UUID `ffe0` (arc.dart:14).

**Bytes.** TARE = `54` (`T`); TIMER_START = `52` (`R`); TIMER_STOP =
`53` (`S`); TIMER_RESET = `43` (`C`). **All single-byte ASCII; match
across both apps.**

**Reliability.** Neither double-sends. Neither has a watchdog. **Match.**

**Lifecycle.** Legacy: `after 2000 felicita_enable_weight_notifications`
(a 2-second hold-off). Reaprime: subscribe immediately. The 2 s legacy
delay was probably a workaround for a service-discovery race that
modern BLE stacks no longer hit; **defer to reaprime** unless real
hardware proves otherwise.

**Notification parsing.** Both expect 18-byte frames. Legacy decodes
weight as 6 ASCII digits with sign byte at offset 2 (`bluetooth.tcl:409-435`).
Reaprime does the same via `data.slice(3, 9).map((value) => value - 48).join('')`
(arc.dart:125-146). Match. Both also decode the battery byte at offset
15. Match.

**Notable bugs.** None at the write layer.

---

### Hiroia Jimmy

**Capability detection.**
- *Legacy:* name prefix `HIROIA JIMMY` (bluetooth.tcl:2206).
- *Reaprime:* service UUID `06c31822-…-febc93e3bece` (hiroia_scale.dart:13).

**Bytes (TARE).** Both = `07 00`. **Match** (`bluetooth.tcl:691`,
`hiroia_scale.dart:93`).

**Bytes (TOGGLE_UNIT).** Reaprime adds a private `_sendToggleUnit()`
sending `0B 00` (hiroia_scale.dart:120-128). Legacy has no equivalent.
**Defer to reaprime** if Hiroia users hit non-grams display state.

**Reliability.** Neither double-sends. Neither has a watchdog.

**Lifecycle.** Legacy: `after 200 hiroia_enable_weight_notifications`.
Reaprime: synchronous.

**Notification parsing.** Reaprime has an **auto-recovery branch**: if
`mode = data[0] > 0x08`, send `_sendToggleUnit()` and skip the frame
(hiroia_scale.dart:131-139). Legacy decodes whatever shows up.
**Reaprime is more robust.**

The weight decode logic differs subtly: legacy uses signed int from
bytes 3-6 with high-bit branch at `weight >= 8388608` (24-bit signed)
(`bluetooth.tcl:697-711`); reaprime uses 16-bit unsigned + sign byte at
offset 6 (hiroia_scale.dart:141-148). These will agree for weights
within ±6553.5 g but diverge above; espresso shots stay well below
that, so the difference is academic. **Match in practice.**

**Notable bugs.** None.

---

### Skale II (Atomax)

**Capability detection.**
- *Legacy:* name prefix `Skale` → `atomaxskale` (bluetooth.tcl:2190).
- *Reaprime:* service UUID `ff08` (skale2_scale.dart:19).

**Bytes.** TARE = `10`; TIMER_START = `DD`; TIMER_STOP = `D1`;
TIMER_RESET = `D0`; ENABLE_GRAMS = `03`; DISPLAY_WEIGHT = `EC`;
SCREEN_ON = `ED`; SCREEN_OFF = `EE`. **All eight bytes match across
both apps** (`bluetooth.tcl:194-329`, `skale2_scale.dart:107-264`).

**Reliability.** Neither double-sends. Legacy explicitly notes battery
read returns 100% on this scale ("atomax Bluetooth has a bug where
battery level is always reported as 100%, so no point in fetching it",
bluetooth.tcl:2348-2351) and hardcodes battery to 100. Reaprime
**reads `0x180F/0x2A19`** via `_initScale()`
(skale2_scale.dart:127-137) and surfaces whatever value the scale
returns. **Legacy is more honest** — but the behaviours converge if
the firmware is in fact buggy.

**Lifecycle.** Legacy: on connect, sends LCD-on + notif-enable @
1000 ms + button-enable @ 2000 ms + LCD-on @ 3000 ms (redundant)
(bluetooth.tcl:2353-2356). Reaprime: subscribe, optional button
subscribe, battery read, display-on, display-weight, set-grams (all
inside `_initScale`, no delays). **Reaprime is more linear; legacy's
3-second LCD-redundant write was a 2018-era robustness measure**.
Defer to reaprime.

**Notification parsing.** Legacy enables notifications on `EF81` and
decodes from the 7-byte payload (per legacy convention, but no
`skale_parse_response` proc exists — the generic frame handler routes
it). Reaprime explicitly decodes 4-byte little-endian int32 / 2560
(skale2_scale.dart:208-229). **Match in concept; reaprime's decode is
explicit.**

**Notable bugs.** Reaprime trusting the battery byte despite the
documented firmware bug is a Tier 3 cosmetic concern. Defer to legacy
here (force 100% for Skale).

---

### Smartchef

**Capability detection.**
- *Legacy:* name prefix `smartchef` (lower-case; bluetooth.tcl:2230).
- *Reaprime:* service UUID `fff0` (smartchef_scale.dart:13) — same
  collision as Decent/Eureka/Solo/Varia.

**Bytes (TARE).** Both have NO BLE tare command — Smartchef firmware
does not support software tare. Legacy `bluetooth.tcl:1429-1443` shows
the proc body commented out and pops a popup telling the user to press
the physical button. Reaprime implements **software tare**: records
`_weightAtTare = _lastRawWeight` and subtracts it on every snapshot
(smartchef_scale.dart:91-95, 130). **Reaprime is more useful** — the
user gets a tared reading even though the scale isn't aware.

**Reliability.** Neither double-sends. No watchdog.

**Lifecycle.** Legacy: `after 100 smartchef_enable_weight_notifications`.
Reaprime: synchronous.

**Notification parsing.** Both decode bytes [5,6] as 16-bit weight,
sign branch on `data[3] > 10`, divide by 10. Match.

**Notable bugs.** None. Reaprime's software-tare is an *addition*, not
a bug.

---

### Solo Barista (LSJ-001)

**Capability detection.**
- *Legacy:* name prefix `LSJ-001` (bluetooth.tcl:2212).
- *Reaprime:* **no Solo Barista directory under `impl/`**. Reaprime
  ships no Solo Barista implementation. Legacy carries seven procs
  that are byte-for-byte duplicates of Eureka Precisa
  (`bluetooth.tcl:805-894`). Crema deduplicates this with a Rust
  re-export per docs/29.

The implication: **for Solo Barista, Crema's "defer to reaprime" rule
has no target.** Crema should defer to legacy on byte-level details
and copy the Eureka Precisa lifecycle/parsing behaviour wholesale —
which is the docs/29 design. **No action needed.**

**Notable:** the absence of a reaprime impl reflects a real gap in
reaprime's scale coverage, not a deliberate omission.

---

### Varia Aku

**Capability detection.**
- *Legacy:* name prefix `AKU MINI` OR `Varia AKU` (bluetooth.tcl:2193-2195).
- *Reaprime:* service UUID `fff0` (varia_aku_scale.dart:22) — same
  collision concern.

**Bytes (TARE).** Both = `FA 82 01 01 82`. **Match**
(`bluetooth.tcl:1580`, `varia_aku_scale.dart:107`).

**Reliability.** Neither double-sends. No watchdog.

**Lifecycle.** Legacy: no scale-specific connect delay (the Varia AKU
isn't in the post-connect `if {scale_type == …}` ladder at
bluetooth.tcl:2328-2410). Reaprime: synchronous register on connect.

**Notification parsing.** Both decode the `FA <command> <length>
<payload> <xor>` framing. Both handle `command == 0x01 && length == 0x03`
as weight (3-byte BE with high-nibble sign), and `command == 0x85 &&
length == 0x01` as battery. **Match** at the byte level. Note: legacy
at `bluetooth.tcl:1617` writes `set ::de1(scale_battery_level) battery`
— that's a literal string `battery`, not the variable `$battery`.
⚠️ **Legacy has a Tcl bug** (missing `$`): the battery byte never
actually populates the variable. Reaprime correctly assigns
`_batteryLevel = data[3]` (varia_aku_scale.dart:160). **Defer to
reaprime** for battery decode.

**Notable bugs.** Legacy missing-`$` bug above.

---

### BlackCoffee (reaprime-only)

**Capability detection.**
- *Legacy:* not supported.
- *Reaprime:* service UUID `ffb0` (blackcoffee_scale.dart:13).

**Bytes (TARE).** **No BLE tare command.** Reaprime implements
software-tare via `_weightAtTare` offset (blackcoffee_scale.dart:91-96,
135) — same pattern as Smartchef. Legacy has no equivalent because
legacy doesn't support this scale at all.

**Reliability.** No double-send, no watchdog.

**Lifecycle.** Standard reaprime pattern — subscribe on connect.

**Notification parsing.** 7+ byte frames; bytes 3-6 are big-endian
signed int32 in milligrams; sign branch on `data[2] >= 128`. The decode
includes the `_weightAtTare` subtraction.

**Timer.** All three timer methods are no-ops.

**Notable bugs.** None observed; reaprime is the source of truth for
this scale.

**Crema action:** add a BlackCoffee codec module + name-prefix entry
("BLACK COFFEE" or whatever the advertised name actually is — reaprime
doesn't pin the prefix; needs investigation). Tier 3 since the scale
is rare.

---

## Cross-scale patterns

### Capability detection philosophy

Legacy is **uniformly name-prefix-driven**: every scale variant is
identified at scan time by a hardcoded `[string first "<PREFIX>" $name]
== 0` check (bluetooth.tcl:2190-2233). Pros: doesn't require connecting
to read services; one-pass scan classification. Cons: vendors can ship
two scales with the same prefix; firmware updates can rename
advertised devices.

Reaprime is **service-UUID-driven** for most scales, with one
auto-detect (Acaia IPS vs Pyxis) and several scales sharing the
generic `fff0` service. Pros: matches what the hardware actually
exposes. Cons: requires a connect-discover cycle before classifying;
`fff0` is ambiguous across 5 scales.

**For Crema's app layer:** the cleanest model is *both* — name prefix
for scan-time classification (Crema's tier matches legacy here), then
service-UUID validation at connect time as a sanity check. Reaprime's
sole-UUID approach would mis-route Eureka frames to a Decent Scale
codec since both advertise `fff0`.

### Reliability mitigation

| Mitigation | Decent | Acaia | Solo Barista | Skale | Other |
|---|---|---|---|---|---|
| Legacy double-send on writes | ✓ (1.0 firmware bug) | — | ✓ (200 ms before solo_barista_*) | — | — |
| Legacy 1 s heartbeat | ✓ | only PROCH | — | — | — |
| Legacy `after`-scheduled connect | ✓ (5 calls) | ✓ (4 calls) | ✓ | ✓ (4 calls) | small |
| Reaprime watchdog-retry flag | ✓ (`_watchdogRetryAttempted`) | ✓ (`_receivingNotifications`+timer) | — (no impl) | — | — |
| Reaprime 4 s heartbeat | ✓ | 3 s | — | — | — |
| Reaprime init-retry loop (up to N tries) | — | ✓ (10×) | — | — | — |
| Reaprime tare 3× spacing | — | ✓ (Pyxis workaround) | — | — | — |

The pattern: **reaprime systematizes reliability per-scale; legacy
hardcodes it as `after` calls.** The reaprime approach is closer to
what a Rust + Kotlin shell would write, and Crema's transport layer
should mirror reaprime's policies (watchdog + bounded retry) rather
than legacy's blind double-sends.

### Version detection

Neither app does *firmware* version detection at the per-scale codec
layer. Legacy has comments referencing "Decent Scale v1.0 vs v1.1"
but treats them identically and just always double-sends. Acaia has
the IPS-vs-Pyxis fork, but that's protocol detection, not firmware
detection. **No scale in the matrix offers a "read firmware version"
characteristic that either app reads** — the Bookoo's `QUERY_SERIAL`
(present in Crema only per docs/29) would be the closest, but neither
legacy nor reaprime invokes it.

### Lifecycle hooks

Legacy: a single `de1_ble_handler` event handler in
`bluetooth.tcl:2134-2986` switches on event type. Scale connect lands
in the `connected` branch at `bluetooth.tcl:2312+` which dispatches
to a tall `if {scale_type ==}` ladder. The unified `::device::scale`
namespace in `device_scale.tcl:121-484` exposes `tare`, `is_connected`,
state-change callbacks (`on_major_state_change`,
`on_flow_change_manage_timer`).

Reaprime: each scale class implements `onConnect()`, `disconnect()`,
`tare()`, `startTimer/stopTimer/resetTimer`, `sleepDisplay`,
`wakeDisplay`. No global event scheduler — each scale owns its own
Timers. Cancellation is per-Timer.

For Crema, this is the **shell-side vs codec-side split** that PR-E
landed on. Codec = bytes only; shell = scheduling + lifecycle. The
reaprime per-scale class is more honest than legacy's giant `if`
ladder.

### Capability gating

Legacy: implicit — `scale_enable_grams` is a no-op for scales that
don't have a unit-toggle (bluetooth.tcl:178-189). Legacy's
`scale_enable_button_notifications` only handles atomaxskale, no-ops
for everything else (bluetooth.tcl:168-176). It's dispatch-and-let-it-
fall-through.

Reaprime: no explicit capability traits, but each `Scale` interface
method has a sensible per-scale implementation (often a no-op with a
comment). Reaprime relies on `sleepDisplay()` → `disconnect()` as the
universal fallback (Felicita, Difluid, Hiroia, Bookoo, Eureka, Varia,
BlackCoffee all do this; Skale and Decent override).

Crema has `Scale::capabilities()` + `is_decent_scale()` (per docs/29).
This is *between* the two extremes — explicit-trait without a
mixin-per-feature explosion. Crema's approach reads cleaner than
either source.

### Notification parsing

In several scales, reaprime decodes fields legacy drops:

- **Atomheart Eclair**: reaprime decodes onboard timer (bytes 5-8);
  legacy doesn't.
- **Bookoo**: reaprime decodes battery (byte 13); legacy decodes only
  weight.
- **Acaia (any protocol)**: reaprime captures battery from
  `msgType == 8`; legacy decodes only weight events 5/11.
- **Varia AKU battery**: reaprime correctly stores it; legacy has a
  Tcl `$`-missing bug that drops it.

In one scale, legacy is stricter than reaprime:

- **Eureka Precisa**: legacy validates the `AA 09 41` 3-byte header
  before decoding; reaprime decodes any 9+-byte frame. Legacy is safer.

### Couplings / ordering

- **Difluid timer-stop**: both apps couple stop-timer to a reset (the
  "stop = pause" convention from the firmware). Legacy at the
  dispatcher layer (`bluetooth.tcl:96-99`); reaprime inside
  `stopTimer()` (difluid_scale.dart:196-210). Behavioural match.
- **Bookoo tare + timer-start**: legacy does NOT couple them. Reaprime
  does NOT couple them. Crema must not couple them either — the user
  decides.
- **Decent Scale disconnect + power-off**: reaprime auto-sends
  power-off on every disconnect (scale.dart:166); legacy only on
  `keep_scale_on=0`. **Honor legacy's user preference**.
- **Atomheart Eclair timer-reset = tare**: both apps share this
  identity (the scale resets timer on a tare command). Codec already
  encodes this per docs/29.

---

## Recommendations for PR G

| Capability | Legacy says | Reaprime says | Crema adopts | Rationale |
|---|---|---|---|---|
| Decent Scale tare counter | rolling 253→0 | hardcoded `0x01` | **rolling** | reaprime bug — debounce drops duplicate tares |
| Decent Scale power-off XOR | `0x09` (computed) | `0x00` (hardcoded) | **`0x09`** | reaprime bug |
| Decent Scale LCD-on second write (`0x04 …`) | not sent | sent with wrong XOR | **defer to reaprime, fix XOR to `0x0C`** | second write is a battery poll worth keeping |
| Decent Scale LCD-off first write (`0x04 0x01 …`) | not sent | sent with wrong XOR | **defer to reaprime, fix XOR to `0x0D`** | extension; verify need on hardware |
| Decent Scale double-send | yes (intentional, 1.0 bug) | no (watchdog) | **no** | 1.1 firmware fixes the underlying drop |
| Decent Scale heartbeat cadence | 1 s | 4 s | **4 s** | BLE-appropriate; PR-E aligned with reaprime |
| Decent Scale BLE watchdog | none | re-subscribe@12s, disconnect@20s | **reaprime model** | strictly better |
| Bookoo timer XOR (start/stop/reset) | wrong (legacy hand-typed) | wrong (copied from legacy) | **structural XOR (Crema-correct)** | both sources buggy |
| Bookoo battery decode | not parsed | parsed (byte 13) | **parsed** | reaprime |
| Acaia init-retry | unbounded `after` loop | bounded 10× | **bounded 10×** | reaprime |
| Acaia 3× tare for Pyxis | not done | done (100 ms spacing) | **done for Pyxis** | reaprime |
| Acaia heartbeat cadence | 2 s (PROCH-only) | 3 s (always) | **3 s always** | reaprime more consistent |
| Acaia battery decode (msgType=8) | not parsed | parsed | **parsed** | reaprime |
| Acaia Pyxis 5 s response watchdog | none | disconnect on timeout | **adopt with longer threshold** | watchdog yes, 5 s too aggressive for BLE |
| Acaia protocol detection | name prefix | service UUID at connect | **name prefix at scan, service UUID at connect (both)** | hybrid is safest |
| Atomheart service UUID | `B905EAEA-2E63-0E04-7582-7913F10D8F81` | `b905eaea-6c7e-4f73-b43d-2cdfcab29570` | **legacy UUID** (pending hardware verification) | reaprime UUID looks synthetic |
| Atomheart onboard timer decode | not parsed | parsed (uint32 LE ms) | **parsed** | reaprime |
| Solo Barista | full 7-cmd codec | no impl | **legacy bytes** (Crema re-exports Eureka) | reaprime gap |
| Eureka Precisa frame header validation (`AA 09 41`) | validated | not validated | **validated** | legacy stricter |
| Felicita 2 s connect hold-off | yes | no | **no** | obsolete BLE workaround |
| Hiroia toggle-unit auto-recovery | not done | sends `0B 00` when unit != grams | **done** | reaprime more robust |
| Skale battery read | forced 100% (legacy firmware bug) | reads `0x180F/0x2A19` | **forced 100%** | legacy's documented bug avoidance |
| Skale connect lifecycle (4 `after`-scheduled writes) | sequenced | linear | **linear (reaprime model)** | the redundant writes were 2018 workaround |
| Smartchef software-tare | not done (popup) | done (`_weightAtTare`) | **done** | reaprime is more useful |
| Difluid auto-correct unit drift | not done | re-sends SET_UNIT_GRAMS | **done** | reaprime more robust |
| Varia AKU battery decode | Tcl `$`-missing bug | correct | **correct (reaprime)** | legacy bug |
| Decent Scale auto-power-off on disconnect | only if `keep_scale_on=0` | always | **respect user setting** | legacy is user-configurable, better UX |
| Acaia `force_acaia_heartbeat` PROCH branch | hardcoded for PROCH | always-on | **always-on** | reaprime simpler |

**Net:** of the 27 decision points above, **~21 defer to reaprime, ~5
defer to legacy, ~1 is hybrid**. Crema's PR G should default-to-reaprime
but explicitly preserve the five legacy-correct branches: Decent tare
counter, Decent power-off XOR, Bookoo timer XOR (Crema-correct
distinct from both), Eureka header validation, Skale battery=100%, and
the Decent auto-power-off being user-controlled.

---

## Open questions

1. **Atomheart Eclair service UUID.** Legacy says
   `B905EAEA-2E63-0E04-7582-7913F10D8F81`; reaprime says
   `b905eaea-6c7e-4f73-b43d-2cdfcab29570`. Need a BLE sniffer trace
   against a real Eclair to determine which is correct. Both share the
   same first 32 bits (`b905eaea`) so vendor identity is consistent —
   one is just wrong on the lower 96 bits.
2. **Bookoo timer XOR.** Both legacy and reaprime ship the wrong
   checksum. Does the Bookoo accept it (firmware ignores XOR), or has
   the timer been silently broken in both apps? Crema's bytes are
   structurally correct; need a sniffer to confirm the scale accepts
   them. Test plan: send Crema-correct timer-start, observe whether
   the scale's onboard timer advances.
3. **Decent Scale battery-poll frame.** Reaprime's
   `0x03, 0x0A, 0x04, …` write inside `_sendOledOn` — is it actually a
   battery poll? With which XOR (the wrong one reaprime sends, or
   `0x0C` Crema would compute)? If Crema adopts it, which checksum
   does the scale accept?
4. **Acaia Pyxis 5 s watchdog threshold.** Aggressive for a BLE link
   on a phone moving in/out of range. Should Crema use 10 s or even
   15 s? Needs field testing.
5. **Reaprime BlackCoffee advertised name prefix.** Reaprime detects
   by service UUID `ffb0`; there's no name-prefix gate documented. PR
   G must decide a prefix (probably "BLACK COFFEE" or "BC Scale") to
   match Crema's name-prefix scan convention.
6. **Skale battery service.** Is the "always 100%" bug still present
   in current Atomax firmware? If newer Skale revisions report battery
   correctly, Crema should read the characteristic and trust it.
7. **Decent Scale tare counter wraparound after 256 tares.** Legacy
   rolls 253→…→255→0. Does the scale firmware reset its "recently
   used" set on counter wrap, or does `0` collide with `0` from the
   previous wrap? Probably fine in practice (256 tares per session is
   a lot) but worth confirming.
8. **Acaia name-vs-service detection.** Legacy uses `ACAIA`/`PROCH`
   to pick IPS protocol and `PEARL*`/`LUNAR`/`PYXIS` to pick Pyxis.
   But reaprime auto-detects by service. A `PEARL` device that
   advertised both `1820` and `49535343-…` services would split.
   Verify on hardware which services PEARLS-class Acaias actually
   expose.

---

## Appendix — file-line references

**Legacy dispatch** (`de1plus/bluetooth.tcl`):

- Scale-type ladder by name prefix: 2190-2233
- Scale-connect lifecycle ladder: 2312-2410
- Generic dispatcher procs (`scale_tare`, `scale_timer_*`,
  `scale_enable_*`): 18-189

**Legacy unified scale namespace** (`de1plus/device_scale.tcl`):

- `::device::scale::tare` dispatch: 321-376
- `on_connect` callbacks (per-scale timer-enable): 1440-1499
- Watchdog (`_watchdog_first_fire` etc): 393-468

**Legacy UUIDs** (`de1plus/machine.tcl`): 73-110

**Per-scale legacy procs**:
- Skale: 194-329
- Felicita: 333-435
- Bookoo: 441-541
- Atomheart Eclair: 544-663
- Hiroia: 667-711
- Eureka Precisa: 714-802
- Solo Barista: 805-894
- Acaia: 897-1171
- Decent Scale: 937-1413
- Smartchef: 1416-1452
- Difluid: 1455-1554
- Varia AKU: 1557-1620

**Reaprime per-scale Dart classes** under
`reaprime/lib/src/models/device/impl/`:
- `acaia/acaia_scale.dart`
- `atomheart/atomheart_scale.dart`
- `blackcoffee/blackcoffee_scale.dart`
- `bookoo/miniscale.dart`
- `decent_scale/scale.dart` (+ `scale_serial.dart` for USB-serial variant)
- `difluid/difluid_scale.dart`
- `eureka/eureka_scale.dart`
- `felicita/arc.dart`
- `hiroia/hiroia_scale.dart`
- `skale/skale2_scale.dart`
- `smartchef/smartchef_scale.dart`
- `varia/varia_aku_scale.dart`

**Related Crema docs**:
- 06-scale-protocols.md — per-scale protocol notes
- 22-reaprime-action-list.md — reaprime-derived TODOs
- 27-write-side-gaps.md — pre-PR-E gaps audit
- 29-scale-completeness.md — codec coverage matrix (the
  byte-count companion to this behavioural audit)

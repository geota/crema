# 19 — Write-path verification (de1-side `WriteCharacteristic` wiring)

**Status:** audit doc, paired with commit `bf747c7`.
**Scope:** the changes that enable `Command::WriteCharacteristic` outputs from
the Rust core to actually reach the DE1 over Web Bluetooth. Before this
commit, every `WriteCharacteristic` command was discarded in the web shell
(`executeCommand` was a no-op for that branch); now it is dispatched.

This doc is for you to verify what landed, line-by-line, before relying on
it. The new write surface is small (one orchestrator path, one target
mapper, one `BleDevice.write` call site) but it broadens what bytes the
shell can put on the wire, so a careful read-through is warranted.

---

## 1. What "write side" means here

The web shell's BLE plumbing has two output paths:

1. **Scale writes** — `Command::WriteScale` → `ScaleManager.writeScale(bytes)`
   → `BleDevice.write(SCALE_SERVICE, SCALE_CHARACTERISTIC, bytes)`. Already
   live and used by tare, mode-switches, etc. *Not changed by this work.*
2. **DE1 writes** — `Command::WriteCharacteristic { target, data }` →
   (previously a no-op) → (now) `De1Manager.writeCharacteristic(target,
   data)` → `BleDevice.write(SERVICE, uuidForWriteTarget(target), data)`.

The motivating use case is the MMR read of register `0x800010`
(`FirmwareVersion`), which on the wire is a *write* to `cuuid_05` (the
"ReadFromMMR" characteristic accepts the 20-byte read-request packet). The
DE1 then responds via the now-subscribed notify on the same characteristic.
This is the legacy app's pattern: `mmr_read` writes the request,
`parse_binary_mmr_read_int` decodes the notify reply
(`de1_comms.tcl:1274-1276`).

A side effect of wiring the path is that other `WriteCharacteristic`
commands the core can emit — the impl agent's recent §3.2 water-level
threshold, §4 MMR register setters, §7.2 Bookoo timer-related core writes
that aren't actually DE1-bound, and the §5/§2/§6 plans not yet
implemented — also now reach the wire if/when they are invoked. Most of
those are gated behind UI surfaces that don't exist yet; **see §6 for the
expected exposure of each target today**.

---

## 2. Targets and their UUID mappings

The full mapping lives in `web/src/lib/ble/de1.ts:uuidForWriteTarget`. Every
core `WriteTarget` variant maps to exactly one GATT characteristic UUID,
defined in `web/src/lib/ble/de1-uuids.ts`. Unknown / unmapped targets are
dropped with a status log (no silent failure).

| `WriteTarget` | UUID const | `cuuid` | Hex   | Purpose | UI exposure today |
|---|---|---|---|---|---|
| `De1RequestedState`  | `REQUESTED_STATE` | `cuuid_02` | `A002` | 1-byte machine-state request (Sleep / Idle / Espresso / …) | **None** — no UI calls `request_machine_state` yet. |
| `De1ShotSettings`    | `SHOT_SETTINGS`   | `cuuid_0B` | `A00B` | steam / hot-water targets bundle | **None** — `set_steam_hotwater_settings` is on `CremaCore` but no settings UI invokes it yet. |
| `De1MmrRequest`      | `MMR_READ`        | `cuuid_05` | `A005` | MMR read request (20-byte read-request packet) | **Active** — `de1.ts` issues `read_mmr(FirmwareVersion)` automatically after the Version characteristic read at connect time. |
| `De1MmrWrite`        | `MMR_WRITE`       | `cuuid_06` | `A006` | MMR register *write* (sets a register value) | **None** — every §4 MMR setter lives on `CremaCore` but no UI invokes them yet. |
| `De1Calibration`     | `CALIBRATION`     | `cuuid_12` | `A012` | calibration read request + write packet | **None** — `read_calibration` exists on `CremaCore` but no UI invokes it yet. |
| `De1WaterLevels`     | `WATER_LEVELS`    | `cuuid_11` | `A011` | refill-threshold write (also the notify-source for tank level) | **None** — `set_refill_threshold` exists on `CremaCore` but no UI invokes it yet. |

**Verify**: open `web/src/lib/ble/de1-uuids.ts` and confirm the six UUID
constants line up with the §2 row, and open `web/src/lib/ble/de1.ts` and
read `uuidForWriteTarget` — the switch should match this table 1:1, with no
silent fallthroughs.

`MMR_READ` is the *only* one that has a UI trigger today (the auto-read
after pairing). The other five mappings exist as plumbing for code paths
that the prior `write-actions-impl` work landed on the core side. They will
only put bytes on the wire when a future UI surface invokes the matching
`CremaCore` method.

---

## 3. End-to-end data flow for the new MMR firmware-build read

This is the only write that fires today, and it's the easiest to trace.

```
       (after Version read in de1.ts connect())
                       │
                       ▼
   core.readMmr(MmrRegister.FirmwareVersion)
                       │  Rust: builds the 20-byte read-request
                       │  packet via mmr::read_request(0x800010, 1)
                       ▼
   CoreOutput { commands: [WriteCharacteristic { target:
                  De1MmrRequest, data: <20 bytes> }] }
                       │
                       ▼   callbacks.onCoreOutput(out)
   CremaApp.applyCoreOutput
                       │
                       ▼   loops over out.commands
   CremaApp.executeCommand(command)
                       │
                       ▼   switch on command.type
   case 'WriteCharacteristic':
       de1.writeCharacteristic(target, data)
                       │
                       ▼   uuid = uuidForWriteTarget(De1MmrRequest)
                       │        = De1Uuids.MMR_READ (cuuid_05)
                       ▼
   device.write(SERVICE, MMR_READ, data)   ─── 20 bytes on the wire
                       │
                       ▼
   DE1 firmware processes the read request
                       │
                       ▼  notifies on cuuid_05 (MMR_READ subscription)
   device.setSink notification
                       │   uuid → 'De1MmrRead' via sourceFor
                       ▼
   core.onNotification('De1MmrRead', bytes, now)
                       │   handle_mmr_read decodes the reply,
                       │   matches register = FirmwareVersion (0x800010),
                       │   sets self.last_firmware_build = Some(value),
                       │   emits Event::MmrValue
                       ▼
   firmware_update_status() now returns
   FirmwareUpdateStatus::UpToDate { installed: 1352 }
   (or UpdateAvailable / NewerInstalled depending on cmp vs
    LATEST_KNOWN_FIRMWARE_BUILD)
```

**Verify**: trace the path in code:

- `core/de1-app/src/lib.rs::read_mmr` — emits the `WriteCharacteristic`
  command.
- `web/src/lib/ble/de1.ts` connect flow, lines around the "MMR read
  FirmwareVersion" step — confirms the dispatch happens *after* the
  Version read.
- `web/src/lib/state/app.svelte.ts::executeCommand` — the new
  `WriteCharacteristic` branch.
- `web/src/lib/ble/de1.ts::writeCharacteristic` — the actual `device.write`
  call.
- `web/src/lib/ble/transport.ts::write` — uses `writeValueWithResponse`,
  which is the equivalent of Android's acknowledged write.

---

## 4. Logging surface

Every DE1 write logs once before the dispatch:

```ts
this.state.log(`→ DE1 write ${command.content.target} ${hex}`);
```

(see `web/src/lib/state/app.svelte.ts:executeCommand`). This mirrors the
existing `→ scale write …` log line — every byte that goes on the wire
appears in the on-screen event log, with the target name and the hex of
the bytes.

If a target has no UUID mapping, `de1.writeCharacteristic` logs a
`DE1 write skipped: no UUID for target <name>` status entry. If the DE1
is disconnected when a write is attempted, it logs `DE1 write skipped:
not connected (<target>)`. If the underlying `device.write` throws (a GATT
error, a stale characteristic), it logs `DE1 write to <target> failed:
<error>` and continues. **No failure is silent.**

**Verify**: open the on-screen event log after a connect — you should see
exactly one `→ DE1 write De1MmrRequest <20-byte hex>` line at connect
time, followed by an `MMR_READ A005` notification arriving (the reply).
Any other `→ DE1 write …` line during normal operation is unexpected
today and would indicate an unintended core emission.

---

## 5. What this commit cannot do (and does not pretend to)

- **`Command::WriteScale`** routing is unchanged — same path as before.
- **No new core methods** were added; the wiring exposes outputs that
  already existed in `CremaCore`.
- **The write characteristic is not subscribed for echoes / acks** beyond
  the existing MMR_READ subscription. The DE1's `RequestedState` / etc.
  characteristics that confirm a write on their own notify side will not
  be observed until those subscriptions are explicitly added.
- **No retry** on transient GATT errors. A `writeValueWithResponse`
  failure logs and moves on; the core does not see the error.
- **No request/reply matching**. An MMR read does not "wait" for the
  reply at the JS layer — the dispatch returns as soon as the write is
  acked, and the reply arrives via the notification sink whenever it
  arrives. The core caches state asynchronously.
- **Capability gating** lives in the core (e.g. scale-only timer methods
  return empty `CoreOutput` when no capable scale is connected). The
  shell trusts the core — `de1.writeCharacteristic` does not look up
  capabilities or refuse writes on its own.

---

## 6. Targets exposed by today's UI vs. tomorrow's

The five non-MMR-read mappings are "loaded guns" — the path to the wire is
open, but no UI yet pulls the trigger. To audit *what bytes can actually
hit the DE1 today*:

```
$ grep -rn "applyCoreOutput\|executeCommand" web/src/lib/state/ \
    web/src/lib/components/ --include="*.ts" --include="*.svelte"
```

The only DE1-bound `WriteCharacteristic` paths reachable from the current
UI are:

1. **MMR FirmwareVersion read at connect time** — `de1.ts` connect flow.
2. *(none else)* — confirm by searching the UI for callers of
   `app.setRefillThreshold`, `app.setSteamHotWaterSettings`,
   `app.requestMachineState`, `app.readMmr`, `app.readCalibration`, any
   `CremaCore` write method. As of this commit, no Svelte component
   invokes any of these.

When a UI surface lands that invokes one of the dormant write methods, it
will start emitting bytes on the wire via the now-live path. The §6 list
in this doc is a snapshot at commit `bf747c7`; refresh it whenever a write
gets a UI control.

---

## 7. Things to verify if you want to be paranoid

These are the audit hooks worth pulling on before this commit hits a real
DE1:

1. **UUID strings**. Every UUID is built from a 16-bit short via
   `short('aXXX')` in `de1-uuids.ts`. Read each row in §2 and confirm the
   short hex matches the cuuid in `docs/02-ble-protocol.md` §1.
2. **MMR read packet bytes**. `mmr::read_request(0x800010, 1)` builds a
   20-byte packet starting `0x00, 0x80, 0x00, 0x10` (length=0, address
   big-endian). After connect, the on-screen event log should show
   exactly that hex.
3. **Notification subscription**. `de1.ts` connect flow now has five
   `startNotifications` calls (State, ShotSample, WaterLevels, MMR_READ;
   plus the Version *read*). Confirm the MMR_READ subscription happens
   *before* the MMR read request is dispatched — order matters or the
   reply will be missed.
4. **Source mapping**. `sourceFor` must return `'De1MmrRead'` for
   `cuuid_05`, otherwise the reply is dropped silently. Verify by
   inspecting the switch.
5. **`writeValueWithResponse` vs. `writeValueWithoutResponse`**. The
   transport uses *with response*, which is acknowledged. Some DE1
   firmware versions may prefer write-without-response for performance
   (e.g. during firmware upload), but for a single MMR read request the
   acknowledged path is the safer choice — matches scale writes and
   surfaces transport errors.
6. **Disconnection invariants**. After `disconnect()` the device is
   nulled; `writeCharacteristic` short-circuits with a status log. Trace
   the disconnect path to confirm no in-flight write can run against a
   disconnected handle.

---

## 8. Rollback

If something goes wrong on a real DE1, the safest rollback is per-piece,
not the whole commit:

- **Make `WriteCharacteristic` a no-op again**: revert just the
  `executeCommand` change in `web/src/lib/state/app.svelte.ts`. The core
  will still build commands; they will be silently dropped, restoring
  the pre-`bf747c7` behavior.
- **Cancel the auto-MMR-read on connect**: revert just the "MMR read
  FirmwareVersion" step block in `de1.ts`. The UI's "Check for updates"
  button still works manually if you also keep the `firmware_update_status`
  wiring; otherwise the comparison reports `Unknown`.
- **Keep the wiring; drop only the MMR_READ subscription**: this would
  leave write requests reaching the DE1 with no path home for replies.
  Not recommended — the subscription is what lets the reply come back.

---

## 9. Summary

- One new BLE subscription (`MMR_READ`, cuuid_05).
- One new mapping (`uuidForWriteTarget`, 6 entries).
- One new `De1Manager` method (`writeCharacteristic`).
- One changed orchestrator branch (`executeCommand`'s
  `WriteCharacteristic` is no longer a no-op).
- One new connect-time dispatch (`readMmr(FirmwareVersion)`).
- Five "loaded but unused" write targets — visible in §2.

The exposure of *new bytes on the wire today* is exactly one packet per
connect: the 20-byte MMR read-request for `0x800010`. Everything else is
infrastructure for future UI to opt into.

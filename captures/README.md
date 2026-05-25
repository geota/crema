# Crema BLE Capture Library

Recorded DE1 and Bookoo-scale Bluetooth sessions, kept as **replayable
fixtures** — a real machine's byte traffic, frozen, so the Rust core's decode
logic can be validated and regression-tested with no DE1, no scale, and no
Bluetooth.

A session with a DE1 and/or a scale connected produces ONE interleaved capture
file: the recorder is shared, and a connection counter opens the file when the
first device connects and closes it when the last disconnects. Replaying that
file reconstructs the whole timeline through a single `CremaCore`, which matters
for scale-aware behaviour like shot-start auto-tare.

## Capture format

JSON Lines — one BLE message per line, no header:

```json
{"t": <u64 ms>, "dir": "in"|"out", "src": "<string>", "hex": "<lowercase hex>"}
```

- `t` — millisecond timestamp. On the device this is `SystemClock.elapsedRealtime()`
  — the same clock value fed to `CremaBridge.onNotification`, so a replay ages
  telemetry exactly as the live session did.
- `dir` — `in` = a notification received from a device (DE1 or scale); `out` =
  a command written to one.
- `src` — for `in`, the `NotificationSource` (`DE1_STATE`, `DE1_SHOT_SAMPLE`,
  `DE1_WATER_LEVELS`, `SCALE_WEIGHT`); for `out`, a short characteristic label —
  e.g. `SCALE_COMMAND` for a Bookoo tare/timer write.
- `hex` — the raw payload bytes, lowercase hex, no separators.

Scale traffic shares the format: `SCALE_WEIGHT` is an inbound weight
notification, `SCALE_COMMAND` an outbound tare/timer write. A capture records no
scale-connection event, so when `replay.rs` meets the first `SCALE_WEIGHT` line
it connects a Bookoo scale on the core itself before decoding it.

The recorder is `android/app/src/main/java/coffee/crema/ble/BleSessionRecorder.kt`.

## Recording a session

The Android app records automatically: connecting a DE1 and/or a scale opens one
`session-<timestamp>.jsonl` in `getExternalFilesDir(null)/captures/` (on the
first device connect), and the in-app status log prints the exact path once. The
file closes when the last device disconnects, so DE1 and scale traffic for a
session land interleaved in the same file. Pull it off the device into this
folder:

```sh
adb pull /sdcard/Android/data/coffee.crema/files/captures/session-<stamp>.jsonl \
    captures/
```

## Replaying

From the Rust workspace, feed a capture through the core:

```sh
cd core
cargo run -p de1-app --example replay -- ../captures/session-<stamp>.jsonl
```

It prints every decoded event with its timestamp, then a summary (messages read,
events decoded, decode errors).

## Captures

### `session-20260517-122732-shot-pull.jsonl`

First end-to-end capture on real hardware — a Pixel 10 phone (Android 16)
connected to a DE1. ~259 s, 1158 BLE messages (1150 `ShotSample` telemetry +
8 `StateInfo`), decoding cleanly to **1170 core events, 0 decode errors**.

| Phase | Decoded |
|---|---|
| Connect, machine heating | `MachineStateChanged` Idle/Heating → Idle/Ready |
| Espresso shot | `ShotStarted`, phases Heating → Preinfusion → Pouring → Ending |
| Shot finishes (manual stop) | `ShotCompleted { duration_ms: 81074, sample_count: 389 }` |
| Machine sleeps / disconnect | `MachineStateChanged` Sleep/Ready |

# Crema BLE Capture Library

Recorded DE1 Bluetooth sessions, kept as **replayable fixtures** — a real
machine's byte traffic, frozen, so the Rust core's decode logic can be validated
and regression-tested with no DE1 and no Bluetooth.

## Capture format

JSON Lines — one BLE message per line, no header:

```json
{"t": <u64 ms>, "dir": "in"|"out", "src": "<string>", "hex": "<lowercase hex>"}
```

- `t` — millisecond timestamp. On the device this is `SystemClock.elapsedRealtime()`
  — the same clock value fed to `CremaBridge.onNotification`, so a replay ages
  telemetry exactly as the live session did.
- `dir` — `in` = a notification received from the DE1; `out` = a command written
  to it.
- `src` — for `in`, the `NotificationSource` (`DE1_STATE`, `DE1_SHOT_SAMPLE`,
  `DE1_WATER_LEVELS`); for `out`, a short characteristic label.
- `hex` — the raw payload bytes, lowercase hex, no separators.

The recorder is `android/app/src/main/java/coffee/crema/ble/BleSessionRecorder.kt`.

## Recording a session

The Android app records automatically: every DE1 connection writes
`session-<timestamp>.jsonl` into `getExternalFilesDir(null)/captures/`, and the
in-app status log prints the exact path on connect. Pull it off the device into
this folder:

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

### `session-20260517-122732.jsonl`

First end-to-end capture on real hardware — a Pixel 10 phone (Android 16)
connected to a DE1. ~259 s, 1158 BLE messages (1150 `ShotSample` telemetry +
8 `StateInfo`), decoding cleanly to **1170 core events, 0 decode errors**.

| Phase | Decoded |
|---|---|
| Connect, machine heating | `MachineStateChanged` Idle/Heating → Idle/Ready |
| Espresso shot | `ShotStarted`, phases Heating → Preinfusion → Pouring → Ending |
| Shot finishes (manual stop) | `ShotCompleted { duration_ms: 81074, sample_count: 389 }` |
| Machine sleeps / disconnect | `MachineStateChanged` Sleep/Ready |

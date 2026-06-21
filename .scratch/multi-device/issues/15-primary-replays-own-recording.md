# 15 — A primary replays its OWN recording as a fake DE1 (hijacks live Bluetooth)

- **Status:** done (gated behind an explicit `replayPrimary` flag, default off)
- **Severity:** P1 (breaks real-device primaries: no live DE1, scale can't connect)
- **Area:** Android (proxy · MainViewModel · BleSessionRecorder)
- **Depends on:** none

## Problem (found on real hardware, 2026-06-20)

A real-device **primary** silently stopped using live Bluetooth and showed a phantom
"DE1 Connected" with no machine present — and its **scale could never reconnect**
("Scanning…" forever), even single-device. Root cause is a self-feeding loop:

1. A primary records its BLE session: `bleRecorder.enabled = role != "secondary"`
   (`MainViewModel` ~L881) → writes `getExternalFilesDir(null)/captures/session-*.jsonl`
   (`BleSessionRecorder` ~L118).
2. On the **next launch**, `startPrimaryMode` → `newestCapture()` scans that **same
   `captures/` dir** (`MainViewModel` ~L2628) and, if it finds a `session-*.jsonl`,
   builds a **`ReplayBleTransport` with a fake `"DE1:REPLAY"`** instead of live BT.
3. So the primary replays its own old recording as a fake DE1. And because the whole
   transport is now the replay, the **scale scan also goes through it** (no real
   radio) → the physical scale is never found.

`BleSessionRecorder` even flagged the hazard (~L73-75): *"…feeds the replay-primary's
newest-capture loop (issue 14)."* That stopped a **secondary** recording; a **primary**
still records and then replays itself.

The replay-from-capture was only ever meant as an **emulator/demo** affordance (stage a
capture via `adb` so a device with no Bluetooth can host mirrors). It must never trigger
from the app's own recordings on a real device.

## Fix (Option A — explicit opt-in, shipped)

`AppPrefs.replayPrimary: Boolean = false` (config JSON; also a toggle in the
"Multi-device (LAN proxy · debug)" settings group on both shells). `startPrimaryMode`
only replays when it's true:

```kotlin
val capture = if (readProxyConfigSync().replayPrimary) newestCapture() else null
```

Read from the prefs file (via `readProxyConfigSync`) so it's correct both at startup
(before `loadPrefs`) and on a live mode switch. Default off → a real primary **always**
uses live BT and can't be hijacked; recordings still happen, they just never auto-replay.
For an emulator demo, set `"replayPrimary":true` in `prefs.json` (or flip the toggle).

## Acceptance / Verify

- 2-emulator: primary + capture staged + `replayPrimary` false (default) → uses live BT
  (`de1=false` in NSD; no fake DE1), capture ignored; with `replayPrimary` true →
  replays (`de1=true`). **Both validated 2026-06-20.**
- Real device: a primary with leftover recordings uses live BT by default — scale
  connects, DE1 correctly shows "Not connected" when none is present.

## Touched files

- `settings/AppPrefs.kt` — `replayPrimary`
- `ui/MainViewModel.kt` — `ProxyConfig`/`readProxyConfigSync`/`startPrimaryMode` gate,
  `MainUiState.replayPrimary`, `setReplayPrimary`, currentPrefs/loadPrefs
- `ui/phone/PhoneSettingsScreen.kt` + `ui/screens/SettingsScreen.kt` — debug toggle

## Comments
<!-- append below -->

**2026-06-20 — done + 2-emulator validated.** Surfaced during the #04 real-scale
session: the user (no real DE1) saw a phantom "DE1 Connected" + a scale stuck on
"Scanning…". Traced to a leftover recording driving the replay-primary. Gated behind
`replayPrimary` (default off). Emulator recipe note: replay-primary tests now require
`"replayPrimary":true` in the staged prefs. Real-device fix needs no capture deletion —
the leftover recordings become harmless once the flag defaults off.

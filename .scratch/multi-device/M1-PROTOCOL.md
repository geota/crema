# M1 — LAN proxy transport: wire protocol & component design

**Branch:** `feat/lan-proxy-transport` · **Date:** 2026-06-18 · **Status:** spec locked, build in progress
**Read first:** [`RESEARCH.md`](./RESEARCH.md) (locked design) · [`HANDOFF-M1-lan-proxy.md`](./HANDOFF-M1-lan-proxy.md) · memory `multi-device-design`

M1 lets a **secondary** device drive the DE1/scale over the LAN with no BLE link of its own. The **primary** owns the one real radio (`NordicBleTransport`) and runs a relay; the secondary implements `BleTransport` as a `ProxyTransport` (a network client) and feeds the same Rust core + UI unchanged. This doc is the protocol + component spec.

---

## 1. Locked decisions

| # | Decision | Note |
|---|---|---|
| 1 | **Framed WebSocket, JSON-per-message, hex payloads** | Browser-friendly (M4); a deliberate superset of the `BleSessionRecorder` line (`{t,dir,src,hex}`) so a wire log replays through `replay.rs`. |
| 2 | **Ktor** (server + client `FrameLink`) | One coherent WS stack; reused by M4 (PWA over LAN-http) and M5 (cloud relay). |
| 3 | **One multiplexed connection**, both devices, addressed by `address` | Mirrors how the real transport multiplexes DE1+scale over one Nordic central. |
| 4 | **M1 is a read-only mirror** | `read`/`observe`/`attach`/`snapshot` proven end-to-end; `write` frames defined but **rejected** by the relay. |
| 5 | **Snapshot = latest-value state/identity chars only** | Counted sample streams (ShotSample, scale weight) are **live-only** — never snapshotted, or the core's sample counters double-count. |
| 6 | **Secondary `write()` sends → relay rejects (`not-authoritative`) → client logs-and-swallows** | Machine-safe, no crash in the unmodified core path, self-corrects from the stream. The clean fix (`read_only` core flag) is M2-T1. |
| 7 | **Single authority over BOTH machine-control and session-config** | A secondary never holds independent control or config; it mirrors and relays requests. Prevents the settings-drift scenario by construction (§9). |

---

## 2. The seam (what the proxy must reproduce)

`android/.../ble/BleTransport.kt` — 7 methods + types. Load-bearing semantics the proxy MUST preserve:

- **`observe()` is lossless, non-conflating, ordered** — the Rust core *counts* every `ShotSample`/weight sample. No `conflate()`, no latest-wins.
- **`Notification.atMs` = the primary's `elapsedRealtime()` stamped at delivery** — forwarded **verbatim**, never re-stamped, so the secondary's core decodes identically to a replayed capture.
- **`connect()` suspends until connected AND services discovered**; `read`/`write` suspend-until-complete-or-throw.

Two facts that make this cheap:
- The **managers already run over any `BleTransport` unchanged** — the secondary's `BleScanner → manager.connect(handle) → observe/seedReads` choreography works as-is *if* `scan()` surfaces the primary's held devices.
- Scale UUIDs are **re-derived locally** from the advertised name via `bridge.connectScale(name)` — the wire carries the **name**, not scale UUIDs.

---

## 3. Topology & framing

`ProxyTransport` (secondary) and `LanRelayServer` (primary) exchange `Frame`s (one JSON object per WS message, discriminated on `"type"`, payload inline). Implemented in `android/.../ble/proxy/Frame.kt` (`PROXY_PROTOCOL_VERSION = 1`, `FrameCodec`, `Hex`).

**Addressing:** `address` = the primary's BT address for a device; `service`/`char` = full lowercase 128-bit GATT UUIDs; `id` = requester-monotonic correlation id (requests/replies only; pushes carry none).

---

## 4. Frame catalog

`C→S` = secondary→primary; `S→C` = pushed. (Names = the `Frame` subclasses.)

| Frame | Dir | Fields | Seam method |
|---|---|---|---|
| `Hello` / `Welcome` / `Denied` | C→S / S→C | v, role, client{id,name}, token? → v, primary{id,name}, authority, roster | handshake (TOFU) |
| `Roster` | S→C | `devices:[DeviceInfo{address,name,kind,state}]` | drives **`scan()`** + seeds `connectionState` |
| `Attach` / `Attached` | C→S / S→C | `{id,address}` → `{id,address,state}` + **snapshot burst** | **`connect()`** |
| `Detach` / `Detached` | C→S / S→C | `{id,address}` | **`disconnect()`** |
| `Read` / `ReadOk` / `ReadErr` | C→S / S→C | `{id,address,service,char}` → `{id,hex}` / `{id,reason}` | **`read()`** |
| `Write` / `WriteOk` / `WriteErr` | C→S / S→C | `{id,address,service,char,hex}` → ack / `{id,reason}` | **`write()`** — M1: always `WriteErr` |
| `Notify` | S→C | `{address,service,char,hex,t,src?}` | drives **`observe()`** |
| `State` | S→C | `{address,state}` | drives **`connectionState()`** |
| *(M2)* `Control` / `ControlOk` / `ControlErr` | C→S / S→C | `{id,method,args}` | relayed user intent → primary's VM router |
| *(M2)* `Config` | S→C | session-config snapshot (§9) | settings-resync on attach |

---

## 5. Attach + snapshot-on-attach (state convergence)

The relay keeps a **per-characteristic last-value cache** fed by every `observe()` emission *and* every `read()` result. On `Attach` it replays that cache as `Notify` frames (the proxy equivalent of `seedReads`), so a fresh secondary core converges instantly to current machine state.

- **Snapshot = latest-wins state/identity chars only** (StateInfo, ShotSettings, WaterLevels, Version/firmware, Calibration, MMR identity).
- **ShotSample + scale weight are NEVER snapshotted** (counted streams → double-count). A mirror joining mid-shot charts from attach-time forward (richer mid-shot replay is an M2 polish).
- Race-safety: register the client to the live fan-out **before** sending the snapshot; duplicate of a state char is idempotent in the core; sample streams (which aren't snapshotted) can't double-count.

---

## 6. Two failure domains — where reconnect lives

- **Proxy network link** (secondary↔primary WS): the **`ProxyTransport` self-heals** (NSD re-resolve → redial → re-`Hello` → re-`Attach` → primary re-snapshots), like the web `BleDevice` reconnect. During a brief blip it reports `CONNECTING`, **not** `FAILED`, so the manager's session loop does **not** tear down / `bridge.reset()`. Only terminal give-up → `FAILED`.
- **Primary's BLE link** (primary↔DE1): a genuine drop arrives as a `State` frame → `FAILED` on the secondary → its manager backoff re-`Attach`es (harmless until the primary's link returns).

Two clean domains, no conflation.

---

## 7. Relay tap — `TappingBleTransport` decorator (managers untouched)

```
bleTransport = TappingBleTransport(NordicBleTransport(app), relayHub)   // primary-relay mode
             = ProxyTransport(discovery)                                 // secondary mode
```

The decorator wraps the real transport and tees every `observe()` emission / `read()` result / `connState` into the `RelayHub` **without back-pressuring the primary's core**: the hub `trySend`s into per-client **bounded** queues; a remote that falls behind is **dropped and resyncs via snapshot** (TCP backpressure → bound → drop-client). **Losslessness is per-consumer** — the primary's core path stays lossless as today; each secondary's own inbound stream is lossless for its core; a slow remote never throttles the primary. Honors "nothing above the transport changes" (only `MainViewModel`'s transport construction at ~`:715` picks the mode).

---

## 8. Discovery (NSD) + pairing

`NsdManager` registers `_crema._tcp` on the WS port, TXT `{v, name, id, kind=de1,scale}`. Secondary discovers/resolves and dials. **Zero new permissions** (`INTERNET` covers socket + NSD; `NsdManager` needs no multicast perm). **TOFU**: M1 auto-accepts on the LAN (full "Allow this device?" UX is M2). A **manual host:port override** is the fallback (two AVDs likely can't cross the emulator NAT for mDNS; the in-process loopback test needs no NSD at all).

---

## 9. Authority, M1 write-handling, and the writes roadmap (LOE)

**Why writes are deferred & how M1 stays safe.** Autonomous core writes (SAW stop, frame-skip) are **indistinguishable** from user writes at the shell seam — both are `Command::WriteCharacteristic{De1RequestedState ← Idle}` (`core/de1-app/src/lib.rs:727-737` user / `:2130,2245` SAW; dispatched identically at `MainViewModel.executeCommand:3181`). You can't *filter* them, so M1 doesn't try: the secondary's `write()` sends a `Write` frame, the relay returns `WriteErr(not-authoritative)`, and the client **logs-and-swallows** (returns without throwing). The machine is never driven by a remote; the secondary's brief SAW divergence self-corrects from the `State` stream.

**Supporting writes (M2) — two tiers (from the write-path LOE, 2026-06-18):**

- **T1 — control relay (size S, ~2–3 d).** (a) Add a `read_only: bool` to `CremaCore` gating the ~30 `out.commands.push()` sites (`core/de1-app/src/lib.rs`) + a 1-line UniFFI `set_read_only()` (`core/de1-ffi/src/lib.rs`); events still fire, only commands are suppressed — so the secondary's core is a clean silent observer (no SAW emission at all). (b) A `Control` frame routes user intent to the primary's existing VM methods: `stopShot`, `requestMachineState`, `setActiveProfile`, `quickAdjustBrew`, `setStopOnWeight`, `tareScale`, `setQc*`, `setActiveBean`. `startShot` relays as **intent** — the primary runs its own orchestration (profile-fingerprint + 500 ms upload guard + QC bake), so that complexity never crosses the wire.
- **T2 — config sync + authority UI (size M, ~+2–3 d).** The `Config` snapshot (below) + an authority indicator ("Viewing as secondary") + profile-upload-state reflection (secondary blocks shot-start while the primary's upload is in flight).

**Risk note:** the `read_only` flag is the one cross-language change; it's low-risk (additive, events unaffected) but pulls a UniFFI regen — hence it lives in **M2-T1**, not the transport milestone.

**Settings-drift fix (your #3) — single config authority + two-layer snapshot.** Config has **one owner: the primary**, exactly like machine control. A secondary mirrors config and relays change-requests; it never holds independent config. So: snapshot-on-attach has two layers — (1) BLE-char state (§5, M1), and (2) an **app/session `Config` block** (M2). When you hand off phone→tablet the config travels with the role; when you then stream tablet→phone, the phone *attaches* and the `Config` snapshot makes it **snap** to the tablet's now-authoritative config. No drift, because config is single-owner and re-synced on every attach.

**`Config` field inventory** (mostly `AppPrefs` — already `@Serializable` — + a few live fields):

| Group | Fields | Source |
|---|---|---|
| **Core (T1 startup)** | `activeProfileId`, `stopOnWeight`, `autoTare`, `authority` | `AppPrefs` / `MainUiState` (authority is new) |
| **QC + units (T2)** | `qcSteamTimeS/FlowMlS/TempC`, `qcHotWaterTempC/VolumeMl`, `qcFlushTimeS/TempC`, `qcGrind`, `grinderModel`, `weight/temp/pressure/volumeUnit`, `maxShotDurationS` | `AppPrefs` / `MainUiState` |
| **Live session (T2)** | `activeBeanId`, `machineProfileUploaded` (`bridge.activeProfileTitle()`), `profileUploadInProgress`/`Progress` | `MainUiState` |
| **Excluded** | `de1Address`/`scaleAddress` (per-device), `themeMode`/`keepScreenOnBrew`/`showDebugPanel`/`chartChannels` (UI-only) | — |

---

## 10. Test strategy (no hardware) & build sequence

Each step ends `cd android && ./gradlew :app:compileDebugKotlin` (or the unit test).

1. **Protocol module** — `Frame` sealed types + `FrameCodec` + `Hex`. ✅ **done** (`ble/proxy/Frame.kt`; `FrameCodecTest` 5/5).
2. **In-process loopback unit test** — `ProxyTransport ↔ RelayHub` over `InMemoryFrameLink`. Asserts **samples-in == samples-out** (losslessness, 500), connState propagation, read round-trip, snapshot-on-attach, write-rejection. ✅ **done** (`FrameLink.kt`/`RelayHub.kt`/`ProxyTransport.kt`; `ProxyLoopbackTest` 1/1). No sockets/NSD/emulator/deps. *(The test drives `hub.onInbound` directly; the `ReplayBleTransport`/`TappingBleTransport` tap is built with step 6.)*
3. **Ktor WS binding** — `LanRelayServer` (Ktor CIO server, embedded on the primary) + `KtorWsFrameLink` (one impl for both server & client `WebSocketSession`). ✅ **done** (`LanRelayWsTest` 1/1 — full mirror over `ws://127.0.0.1`). Ktor `3.1.3`, 6 artifacts.
4. **NSD** — register/discover via `NsdManager`; manual host:port fallback. *(Device-bound — `NsdManager` can't run in a JVM unit test; validated on emulator.)*
5. **`MainViewModel` mode switch** ✅ **done** — `buildBleTransport()` at `:715` branches on a synchronously-read `proxyRole`: NORMAL (`NordicBleTransport`, **default + byte-identical**) / PRIMARY (`TappingBleTransport` over `NordicBleTransport`-or-newest-capture-`ReplayBleTransport` + `LanRelayServer.start()`) / SECONDARY (`ProxyTransport` over `ReconnectingClientLink`); teardown in `onCleared`. `AppPrefs`+`MainUiState` gain `proxyRole`/`proxyPrimaryHost`/`proxyPrimaryPort` (persist+apply+setters); a **debug-panel control** (role selector + host/port) added to `SettingsScreen.kt` (tablet) + `PhoneSettingsScreen.kt`. Restart-to-apply. Full app compiles, **APK assembles**, all 9 proxy tests green.
6. **Replay-primary demo** ✅ **done — validated on 2 emulators (2026-06-19).** Tablet (`emulator-5554`) = PRIMARY replaying `session-…-shot-pull.jsonl` → relay; phone (`emulator-5556`, **no Bluetooth**) = SECONDARY over `ws://10.0.2.2:8089` (`adb forward` → tablet relay port). The phone **live-mirrored a full espresso extraction** over the LAN — EXTRACTION timer, pressure/flow/temp, live shot chart with playhead — matching the tablet. The secondary's autonomous core writes were rejected (`not-authoritative`, read-only mirror), and it **auto-reconnected via the M0 path** (first attach remembered the proxy DE1). Building blocks (`ReplayBleTransport` + `TappingBleTransport`) covered in-process by `ReplayMirrorTest` 1/1.
   - **Demo gotchas:** a capture pushed via `adb push` lands in a shell-owned `captures/` dir the app can't list — `chmod 777` the dir (or place it app-side) so `newestCapture()` finds it, else PRIMARY silently falls back to live-BLE. The relay binds an **ephemeral** port (logged "LAN relay listening on :N"); read it and `adb forward tcp:8089 tcp:N`. Secondary config can be written straight to `files/prefs.json` via `run-as` to skip on-screen typing.

---

## 11. Open items (resolve as we build)

- Ktor server engine (CIO) + WebSockets plugin versions; whether the Ktor client or OkHttp-WS backs the `FrameLink` (OkHttp is already a dep — decide at step 3).
- Authority hand-back if the primary leaves BLE range (explicit-only vs auto-elect) — M3.
- Short-UUID / binary frame compaction — later optimisation; M1 stays JSON-hex for debuggability + capture symmetry.

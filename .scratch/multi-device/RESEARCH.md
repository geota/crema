# Multi-device: handoff, mirroring & auto-reconnect — design research

**Date:** 2026-06-18 · **Status:** research / design proposal (nothing implemented) · **Author:** agent research pass (4 codebase scouts + web)

> Goal: let a phone and a tablet (and, as a stretch, the web PWA) act as **shared remotes/screens to one DE1 + scale** — Spotify-Connect-style "play on this device" handoff, live mirroring of controls/data across both, and robust auto-reconnect underneath it all.

---

## TL;DR

1. **Hard constraint (confirmed in code + by Decent):** the DE1 and the scale each accept **exactly one BLE central**. `BleTransport.kt:8-14` literally documents "a single connection per device." So there are never two live radios on the machine — there is always **one owner ("primary")**; everyone else is a **remote**. This is the Spotify Connect model: one active device, N controllers.

2. **The right architecture is the one you proposed — a proxy at the transport seam.** The `BleTransport` interface (`subscribe / write / read`, reshaped this session) *is* the proxy boundary. The **primary** owns the real radio (`NordicBleTransport`) and runs a tiny relay server; a **secondary** swaps its transport to a `ProxyTransport` (a network socket to the primary) and feeds the **same Rust core + same UI, unchanged**. It literally cannot tell it isn't holding the radio. Mirroring becomes nearly free; handoff becomes a role swap.

3. **One safety rule on top of the dumb tunnel: single control authority.** Stop-on-weight runs *in the app/core*, not firmware (`de1-domain/src/stop.rs`). Two devices each running their own SAW loop would race to send the stop write. So: the **primary is the brain** (owns the control loops); a secondary's buttons are **relayed requests**, not local writes.

4. **Mid-shot handoff is unsafe and unnecessary.** Reconnect does a full `core.reset()`; there's no "resume a live shot into a new core," and the SAW gap = overshoot. The proxy model avoids this: **during a shot you mirror** (primary keeps the radio + SAW), and you only **role-swap when idle**.

5. **Auto-reconnect is the foundation and a standalone win.** The **web already has it** (exponential backoff + subscription replay, `transport.ts:231-244`); **Android has none** and doesn't even remember the device address. Porting the web pattern to Android is independently valuable and is the substrate handoff/mirror reconnection reuse.

6. **Transport:** Android↔Android → **LAN (WebSocket) + mDNS/NSD**, **zero new permissions**, ~50–100 ms. PWA / cross-network → **cloud relay (WSS)** or **WebRTC** (browsers can't reach local sockets). Visualizer is **batch-only** so it can't relay real-time — but its **OAuth account is the natural pairing identity**.

7. **PWA as non-primary is very feasible** (web has the transport seam + WASM core + full UI already) and is **the only way Apple devices ever join** (no Web Bluetooth on iOS Safari).

**Recommended order:** auto-reconnect → LAN mirror (native) → idle handoff → PWA-over-relay.

---

## Decisions locked (2026-06-18, with user)

These supersede the menu in §9 where they overlap.

- **Transport: LAN-first.** mDNS/NSD + a WebSocket server on the primary. Zero new permissions, private, offline-capable. A cloud relay is a later **additive** layer (M5) for hosted-PWA + away-from-home — *same framed protocol over a different socket*, so this choice locks nothing out.
- **Pairing: TOFU approve-on-primary.** mDNS discovery + a one-tap "Allow this device?" on the primary, remembered (AirPlay/Chromecast first-use feel). Visualizer-account rooms layer on **only** for the relay path. QR/PIN held in reserve for untrusted networks.
- **Mirror and Handoff are TWO SEPARATE features** (this is the core model):
  - **Mirror** — a secondary joins the primary's live session over the LAN as a **synced screen + relayed-control remote**. Both devices stay active; nobody releases the radio; the primary remains the controller (and SAW authority). Available **anytime, including mid-shot**. **Never defers.**
  - **Take over / Handoff** — *moves the radio*: the primary releases the DE1/scale and the secondary acquires them, swapping the primary role. Needed because the radio owner must stay in BLE range ("I'm leaving with my phone — tablet, take it"). **Idle-only.**
  - The two are independent actions in the UI: *Mirror* is always available; *Take over* is enabled only at idle. Mid-shot you simply **Mirror** (instant live view + control), and *Take over* lights up when the shot finishes.
  - **Mid-shot *Take over*: disabled.** Mirror covers the mid-shot view + control; *Take over* re-enables when the machine returns to idle. No auto-defer / pending-takeover state.

---

## 1. The hard constraint

| Evidence | Source |
|---|---|
| "talks to exactly two devices … a single connection per device" | `android/.../ble/BleTransport.kt:8-14` |
| Web `BleDevice` = one instance per device, per-device serial `GattQueue`, single sink | `web/.../ble/transport.ts:142-180` |
| Scale is independent of DE1; "Two simultaneous connections are fine" (DE1 *and* scale, not two centrals on one) | `android/.../ble/ScaleBleManager.kt:21-54` |
| Decent's own guidance: to use a different tablet you **re-pair** | decentespresso.com (web search) |

A BLE peripheral's GATT server accepts one central; a second central's `connect()` is rejected at the stack level. **This is not an app limitation we can engineer around** — it's the radio. Everything below respects it.

**Consequence:** exactly one device holds the radio at a time. Call it the **primary** (a.k.a. host/owner/active device). Others are **remotes**.

---

## 2. Architecture: proxy at the `BleTransport` seam

The insight: **a secondary device doesn't need a BLE link, it needs a *transport*.** Crema's whole stack sits on a thin transport interface:

```
            PRIMARY (owns the radio)                    SECONDARY (remote)
   ┌────────────────────────────────────┐     ┌────────────────────────────────────┐
   │  Compose UI / Svelte UI            │     │  Compose UI / Svelte UI  (SAME)    │
   │  MainViewModel / CremaApp          │     │  MainViewModel / CremaApp (SAME)   │
   │  Rust core (UniFFI / WASM)         │     │  Rust core (UniFFI / WASM) (SAME)  │
   │  ── BleTransport (subscribe/       │     │  ── BleTransport ───────────────┐  │
   │       write/read) ──┐              │     │     = ProxyTransport            │  │
   │  NordicBleTransport │  + Relay ◄───┼─────┼──► (network client) ───────────┘  │
   │       │  (real BLE) │    server    │ LAN │                                   │
   └───────┼─────────────┴──────────────┘ WSS └────────────────────────────────────┘
        ╭──┴──╮   ╭───────╮
        │ DE1 │   │ scale │   (one central each — the primary's)
        ╰─────╯   ╰───────╯
```

- **Primary**: `NordicBleTransport` (Android) / `BleDevice` (web) holds the one real link **and** runs a small relay server.
- **Secondary**: its `BleTransport` is a `ProxyTransport` whose `write` / `read` / `subscribe` are **forwarded over the network** to the primary, which performs them on the real link and **streams notifications back**.
- **Everything above the transport is untouched** — the Rust core's frame parsing, the machine-state model, the ViewModel/stores, the entire UI. That's what makes "both become screens to the same machine" cheap.

**What's on the wire** = GATT-level ops, a tiny framed protocol:
```
→ write(charUuid, bytes)         → read(charUuid) ⇒ bytes
→ subscribe(charUuid)            ← notify(source, bytes, tArrivalMs)
← connState(device, state)       ← presence/role/authority frames (see §3)
```
The primary **multiplexes** its own UI's writes and the secondary's writes onto the one real link (serialize writes through the existing per-device queue), and **fans out** every inbound notification to all connected remotes *and* its own core.

**The seam exists on both shells:**
- Android: `BleTransport` (`subscribe/write/read`); managers call `transport.connect/disconnect`, `writeCharacteristic`, observe merged notifications (`De1BleManager.kt:188-294`). A `ProxyTransport : BleTransport` drops in under `De1BleManager` / `ScaleBleManager` with zero changes above it.
- Web: a **named, intentional** transport interface `De1Transport` (`web/.../ble/de1-transport.ts:37-112` — `setSink / connectGatt / startNotifications / write / readCharacteristic / disconnect` + reconnect callbacks); its design comment already anticipates USB/WebUSB impls, so the seam was *built* for swapping. Core boundary is transport-agnostic data-in/out: inbound `core.onNotification(source, data, atMs)` (`de1.ts:215-241`), outbound `device.write(svc, char, data)` (`de1.ts:290-308`). A `WebSocketTransport : De1Transport` is the **only new type**; the WASM core + `app.svelte.ts` state + UI reuse wholesale.

> The notification→core→command boundary is the *same shape* on both shells (`onCoreOutputJson` on Android `MainViewModel.kt:3093`; `onCoreOutput` on web `de1.ts:231-240`). That symmetry is why one proxy protocol serves all three shells.

---

## 3. Control authority — the rule on top of the tunnel

A pure transport tunnel is **not** enough, because of one safety-critical fact:

- **Stop-on-weight (SAW) is app/core-driven, not firmware-driven.** `AutoStop` runs in `de1-domain/src/stop.rs:1-14,121-150`; when projected weight hits target it emits `Command::WriteCharacteristic{ De1RequestedState ← Idle }` and **the app sends that write**.

If a secondary also saw the raw scale stream and ran its own `AutoStop`, **two devices would race to stop the shot** (and to skip frames, tare, etc.). So:

> **Exactly one device — the primary — is the controller.** It runs the control loops and owns all *autonomous* writes (SAW stop, frame-skip). A remote's button press is a **relayed request** the primary executes; remotes never write to the machine directly.

This is a tiny protocol addition (an `authority`/`role` field + "control request" frames), not a new architecture. It also makes the security story clean: only the primary mutates the machine.

**Command surface a remote relays** (already all funnel through one router on Android, `requestMachineState()` `MainViewModel.kt:1611`):

| Action | Method | file:line |
|---|---|---|
| Start / stop shot | `startShot()` / `stopShot()` | `MainViewModel.kt:1433 / 1629` |
| Load profile | `setActiveProfile(id)` | `:926` |
| Quick-control override | `quickAdjustBrew(...)` | `:961` |
| Stop-on-weight toggle | `setStopOnWeight()` | `:1889` |
| Tare scale | `tareScale()` | `:3045` |
| Sleep / wake / steam / hot-water / flush | `requestMachineState(...)` | `:1611-1620` |
| QC steam/water/flush params | `setQc*` | `:1946-1991` |
| Active bean | `setActiveBean(id)` | `:2278` |

---

## 4. Three features, one foundation

### 4a. Auto-reconnect — **build this first** (standalone value)

**Current state**
- **Web: already solved.** `BleDevice.onGattDisconnected()` (`transport.ts:231-244`) distinguishes unexpected drops from user disconnects and runs exponential backoff (500 ms → 30 s ceiling, ~8 attempts), then **re-resolves characteristics and replays all subscriptions** on the preserved sink (`transport.ts:345-355`).
- **Android: nothing.** `NordicBleTransport` uses `ConnectionOptions.Direct(retry=2)` — *initial-connect* retries only (`NordicBleTransport.kt:175`). A link drop propagates to `DISCONNECTED` but **nothing re-scans or re-connects** (`ble-lifecycle` scout). And **no device address is persisted anywhere** (`AppPrefs.kt:18-82` has no BLE field; `de1BluetoothAddress` is in-memory only) — every connect is a fresh scan.

**Plan (Android)**
1. **Persist the last DE1 + scale address** (add fields to `AppPrefs`). Enables cold-start re-acquire without a user tap.
2. On **unexpected** disconnect (not user-initiated), run a backoff reconnect loop mirroring the web (reuse the remembered `Peripheral` handle in `NordicBleTransport.peripherals` — it survives disconnect — else re-scan by saved address).
3. **Replay subscriptions** after reconnect (the managers already re-`startObserving()`; make that a reconnect path, not only a connect path).
4. Optionally evaluate Nordic `ConnectionOptions.AutoConnect` (OS-managed background reconnect) vs. an app-driven loop — app-driven matches the web and is more controllable.

Independently shippable, and it's the same machinery handoff/mirror lean on (a device re-acquiring its link/role).

### 4b. Mirroring (shared remotes/screens)

- Secondary runs on `ProxyTransport`; primary relays. **Both show identical live state**; the secondary controls via relayed requests (§3).
- **During a shot, mirroring is the only safe multi-device mode** — SAW stays authoritative on the primary; the secondary is a synchronized view + request console.
- Reuses the **entire** UI/state layer on both shells. The secondary's "session" is just the primary's notification stream replayed into its own core.
- New surface needed: a **device/presence list** ("View on Tablet", "2 remotes watching"), an **authority indicator** (who's the brain), and the relay/proxy client+server.

### 4c. Handoff ("play on this device" / transfer the radio)

- **Role swap.** Primary releases the radio:
  ```
  bleScanner.cancel("DE1"); ble.disconnect()       // De1BleManager.kt:158
  bleScanner.cancel("Scale"); scale.disconnect()   // ScaleBleManager.kt:229
  ```
  Secondary promotes `ProxyTransport → real transport`:
  ```
  ble.markScanning(); bleScanner.scanFor("DE1", ::isDe1Name){ d,_ -> ble.connect(d) }   // MainViewModel.kt:1554
  scale.markScanning(); bleScanner.scanFor("Scale", ::isBookooName){ d,n -> scale.connect(d,n) }
  ```
- **Session-context transfer** (the only state the DE1 won't re-volunteer — from the `session-state` scout). Minimal payload:
  ```jsonc
  { "activeProfileId", "brewParams"|null, "activeBeanId",
    "stopOnWeight", "autoTare",
    // only if mid-shot (avoid — see below):
    "shotInProgress", "shotElapsedMs", "shotTelemetry":[…] }
  ```
  Everything else (machine state, telemetry scalars, firmware, MMR, water level) **re-populates automatically** once the new owner connects. QC params/units/etc. are persisted prefs the new device already has.
- **The new owner must re-upload the profile on connect** (`lastUploadedFingerprint` is cleared on disconnect, `MainViewModel.kt:626`) and honor the **+500 ms profile-download guard** before reading machine state (`ProfileSync.profileDownloadGuard`; DE1 firmware blocks during `ProfileDownloadInProgress`).
- **Mid-shot rule (locked):** **do not role-swap during extraction** (gap × flow = overshoot; `core.reset()` wipes SAW). **Take over is disabled mid-shot** — *Mirror* covers the mid-shot view + control, and *Take over* re-enables at idle. No auto-defer.

---

## 5. Transport options

| Transport | Latency | New Android perms | Backend | PWA-capable | Notes |
|---|---|---|---|---|---|
| **LAN WebSocket + NSD/mDNS** | ~50–100 ms | **none** (INTERNET covers) | none | only if PWA served over LAN-http (mixed-content blocks hosted HTTPS→ws://) | **best native↔native** |
| Raw LAN TCP/UDP | ~50 ms | none | none | ❌ (browsers can't) | lower-level than needed |
| **Cloud relay (WSS)** | ~200–500 ms | none | **yes** (host a relay) | ✅ | best for PWA + cross-network/NAT; reuse Visualizer OAuth as identity |
| WebRTC DataChannel | ~50–150 ms | none | signaling + STUN/TURN | ✅ | P2P, browser-friendly, but most complex |
| Nearby Connections | ~50 ms | `NEARBY_WIFI_DEVICES` **+ GMS dep** | none | ❌ | adds a Google dependency; skip |
| Visualizer as relay | n/a | none | n/a | n/a | **batch-only REST** (`visualizer-call.ts:43`), not real-time — can't relay |

**Recommendation:**
- **Phase A (native↔native): LAN WebSocket server on the primary + NSD discovery.** Zero new permissions, low latency, no backend, fully local/private. A *framed WebSocket* (not raw socket) is chosen deliberately so the **same protocol** can later serve browser clients.
- **Phase B (PWA + remote/cross-network): cloud relay over WSS**, with **Visualizer OAuth as the pairing identity** (both devices already sign into the same account → match them into a "room"; §6). Optionally WebRTC later if relay latency/cost matters.

---

## 6. PWA as a non-primary (the follow-up)

**Feasibility: high — the web is *already split at the transport layer*.** It has (a) a named transport interface `De1Transport` (`de1-transport.ts:37-112`) whose design comment already anticipates USB/WebUSB — the seam was intentional; (b) the **same Rust core as WASM** behind a transport-agnostic data-in/out boundary (`de1.ts:215-241` / `290-308`); (c) the full UI driven off `app.svelte.ts`, identical for primary and mirror. A **`WebSocketTransport : De1Transport`** is the **only new type** — core, state, and UI reuse wholesale.

- **iOS is the killer use-case.** iOS Safari has **no Web Bluetooth**, so an iPhone/iPad can *never* be a primary. As a network remote it joins fine — **this is the only way an Apple device participates at all.** The web already feature-detects via `isWebBluetoothSupported()` (`transport.ts:590-593`) and degrades gracefully (surfaces "Web Bluetooth not available"); absence → non-primary-only.
- **The web can already be a *primary*** on Android Chrome via Web Bluetooth (existing behavior). The **new** capability is *non-primary mirror*.
- **Transport wrinkle (decides Phase B):** a **hosted HTTPS PWA cannot open `ws://` to a local IP** (mixed-content) and Chrome's **Private Network Access** further blocks HTTPS→private-IP. Three ways out:
  1. **Cloud relay (WSS)** — both ends dial out; sidesteps all local-network/browser rules and covers NAT/remote. *Recommended for PWA.*
  2. **WebRTC** — P2P with cloud signaling; browser-friendly, lower latency, more moving parts.
  3. **Serve the PWA from the primary over LAN-http** (`http://<primary-ip>`) so it's same-origin with the `ws://` — works, but a different deployment than the hosted app.
- **PWA infra: ready.** Workbox service worker (`web/build/sw.js`, asset-precache only — **won't interfere** with a `ws://` link), standalone Web App Manifest (`static/manifest.webmanifest`), adapter-static SPA. No existing realtime (expected — local-first). Mirror = add the `WebSocketTransport` pointed at `ws://<primary>/de1-bridge`; a mirror can't run offline (it needs the primary's stream). The core even has a `beginReplay()` mode (`core/index.ts:337-345`).

---

## 7. Risks, edges & "is it worth it"

- **Proxy multiplexing edges:** serialize writes from multiple controllers (reuse the existing per-device GATT queue); fan-out notifications to all remotes + local core; handle a remote dropping mid-stream; handle the **proxy link itself** reconnecting (same backoff machinery as 4a, one layer up).
- **Authority hand-back:** if the primary dies/leaves BLE range, do remotes auto-promote one to primary? (Define: explicit only, or auto-elect with a guard.)
- **Latency budget:** telemetry is ~25–40 Hz; a few-ms LAN hop is invisible for *viewing*. Relay (~hundreds of ms) is fine for viewing and discrete commands, **not** for running SAW remotely — which is exactly why SAW stays on the primary (§3).
- **Security/trust:** local pairing (QR/PIN on same LAN) vs. Visualizer-account-scoped rooms. Relay must authenticate (don't let strangers join your machine). Only the primary mutates the machine.
- **Profile-download contention:** if a remote requests a profile load, the **primary** performs the upload + 500 ms guard; remotes must reflect `profileUploading` state and not issue machine reads during it.
- **Complexity honesty:** this is a real subsystem (relay protocol, discovery, presence, authority, reconnect, pairing). **Auto-reconnect (4a) is a clean, high-value slice that ships on its own**; mirroring/handoff are a larger build — stage them.

---

## 8. Staged build plan

**M0 status (2026-06-18): ✅ implemented + emulator-verified UI; reconnect behavior pending a real-DE1 test (emulator has no BLE).** Both managers now observe `transport.connectionState` and run a backoff session loop (500 ms→30 s, 8 attempts) on an unexpected `FAILED`, re-subscribing on reconnect; `AppPrefs` persists `de1Address`/`scaleAddress`/`autoReconnect`; `loadPrefs` cold-start auto-connects; `setAutoReconnect`/`forgetDe1`/`forgetScale` in the VM; Settings get an **Auto-reconnect** toggle (both shells) + **Forget this DE1** (both) / Forget scale (tablet). Verified on the phone emulator: toggle persists to `prefs.json`, Forget clears the address per-device.

| Milestone | Scope | Depends on |
|---|---|---|
| **M0 — Android auto-reconnect** ✅ | Persist last DE1/scale address (`AppPrefs`); backoff reconnect + subscription replay on unexpected drop (ported the `transport.ts` pattern) + cold-start auto-connect + disable-toggle + per-device Forget. | — (standalone) |
| **M1 — Proxy protocol + LAN transport** | Framed `write/read/subscribe/notify/role` protocol over the `BleTransport` seam; LAN **WebSocket server** on primary; **NSD** discovery; `ProxyTransport : BleTransport` (Android). | M0 |
| **M2 — Mirror mode (native)** | Device/presence list, authority indicator, relayed control-requests, "View on …" entry. | M1 |
| **M3 — Handoff (idle)** | Role-swap (release/acquire radio) + session-context transfer; "Play on this device" UI; mid-shot → mirror-and-defer. | M1, M2 |
| **M4 — PWA non-primary** | `WebSocketTransport : De1Transport` in `web/`; reuse web UI (`app.svelte.ts`) as mirror; iOS path. | M1 (protocol) |
| **M5 — Cloud relay (WSS)** | Hosted relay; Visualizer-OAuth-scoped rooms; enables PWA-when-not-LAN + cross-network monitoring. | M4 |

**Reuse inventory** (don't rebuild): `BleTransport` seam (both shells), Rust core (UniFFI + WASM), `StoredShot`/`Profile` v2 JSON serialization (`history/v2-export.ts:41`, `export_v2_json_profile`), Visualizer OAuth identity (`AndroidManifest.xml:47-52`, `TokenVault`), the web's reconnect logic (`transport.ts`), the single command-router (`requestMachineState()` `MainViewModel.kt:1611`).

---

## 9. Decisions — status

1. **Transport:** ✅ **LAN-first** (cloud relay is the later M5 additive layer).
2. **Mid-shot Take-over:** ✅ **disabled mid-shot** — Mirror covers it; re-enables at idle. No auto-defer.
3. **Pairing/trust:** ✅ **TOFU approve-on-primary** (account-rooms only for the relay path).
4. **Mirror vs Handoff:** ✅ **two separate features** (Mirror anytime; Take-over idle-only).
5. **Scope / where to start:** ⏳ open — recommended: ship **M0 auto-reconnect** first (standalone, already requested), then M1 (LAN proxy transport).

---

### Appendix — source map (grounded by the research pass)

- **BLE lifecycle / no-reconnect / no-address-persistence:** `MainViewModel.kt:1550/1562/2830/2837`, `De1BleManager.kt:112/158/188-294`, `NordicBleTransport.kt:95/175/225`, `AppPrefs.kt:18-82`, `AndroidManifest.xml` (BLUETOOTH_SCAN/CONNECT, INTERNET).
- **Session state / telemetry / commands:** `MainViewModel.kt:3093/3174` (core seam + telemetry), state inventory across `MainUiState`, control methods `:926-3045`, `pushStopTargets :983`, `lastUploadedFingerprint :626`.
- **Protocol constraints:** `BleTransport.kt:8-14` (single central), `de1-protocol/src/profile.rs:1-10` (autonomous frames), `de1-app/src/lib.rs:2022-2024` (`core.reset()`), `de1-domain/src/stop.rs:1-14,121-150` (app-side SAW), `ScaleBleManager.kt:21-54`.
- **Web transport/reconnect/core seam:** named interface `De1Transport` `de1-transport.ts:37-112`; `BleDevice` impl `transport.ts:150-582` (reconnect `:231-244/345-355`, `isWebBluetoothSupported :590-593`); core boundary `de1.ts:215-241` (in) / `290-308` (out); mirror UI state `app.svelte.ts`; PWA `build/sw.js` + `static/manifest.webmanifest`; `core/index.ts:337-345` replay.
- **Infra/identity/serialization:** `visualizer-call.ts:43` (batch REST), OAuth PKCE `AndroidManifest.xml:47-52` + `TokenVault`, `history/v2-export.ts:41`, `core/bindings/crema-core.kt` (`StoredShot`/`Profile`).

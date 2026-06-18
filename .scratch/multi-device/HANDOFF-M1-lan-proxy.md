# Handoff — M1: LAN proxy transport (`feat/lan-proxy-transport`)

**Branch:** `feat/lan-proxy-transport` (off `main` @ `3046b99`, pushed). **Date:** 2026-06-18.
**Read first:** [`RESEARCH.md`](./RESEARCH.md) (the locked design) + memory `multi-device-design`.

This is **M1** of the multi-device feature. M0 (Android per-device auto-connect + auto-reconnect) shipped on `main`. M1 builds the **proxy transport** that lets a *secondary* device drive the DE1/scale over the LAN without its own BLE link.

---

## The locked design (don't re-derive — see RESEARCH.md)

- **Single BLE central** per DE1 + scale (`android/.../ble/BleTransport.kt:8-14`). So there's always **one owner ("primary")** holding the real radio; everyone else is a **remote**. (Spotify-Connect model.)
- **Architecture = proxy at the `BleTransport` seam.** The primary owns `NordicBleTransport` + runs a relay; a secondary implements `BleTransport` as a **`ProxyTransport`** (a LAN network client) and feeds the **same Rust core + UI, unchanged**. The `subscribe / write / read` interface *is* the proxy boundary.
- **LAN-first** (mDNS/NSD + a WebSocket/socket server on the primary). **Zero new Android permissions** — `INTERNET` covers sockets + NSD (Nearby would add `NEARBY_WIFI_DEVICES` + GMS — avoided). Cloud relay (WSS) is a *later* M5 layer; same framed protocol, different socket.
- **Single control authority:** the primary runs the control loops (stop-on-weight is app/core-driven, `de1-domain/src/stop.rs`); a remote's button press is a **relayed request**, never a direct machine write. A pure dumb tunnel is NOT enough — two SAW loops would race.
- **Mirror ≠ Handoff** (both later milestones). M1 is just the transport plumbing.

---

## M1 scope (this branch)

1. **Wire protocol** — a small framed protocol over the seam: `write(charUuid, bytes)`, `read(charUuid)→bytes`, `subscribe(charUuid)`, `notify(source, bytes, tArrivalMs)`, `connState(device,state)`, plus a `role`/`authority` field (see §3 of RESEARCH). Design it so the *same* protocol later serves browser clients (so prefer a framed **WebSocket**, not a raw socket).
2. **Relay server (primary)** — runs alongside `NordicBleTransport`; forwards remote `write`/`read`/`subscribe` onto the one real link, **multiplexes** writes (serialize through the existing per-device GATT queue), and **fans out** every inbound notification to all remotes *and* the primary's own core.
3. **`ProxyTransport : BleTransport` (secondary)** — a drop-in under `De1BleManager`/`ScaleBleManager` whose `connect/subscribe/write/read/connectionState` are network round-trips to the primary. Nothing above the transport changes.
4. **Discovery** — NSD (`android.net.nsd.NsdManager`) so a secondary finds a primary on the LAN; pairing is **TOFU** ("Allow this device?" on the primary, remembered) — but full pairing UX can be M2; M1 can hardcode/auto-accept on the LAN to prove the transport.

**Out of scope for M1:** mirror UX (M2), handoff/role-swap (M3), PWA client (M4), cloud relay (M5).

---

## Key files / seams

- **`android/.../ble/BleTransport.kt`** — the interface `ProxyTransport` implements; also the relay's vocabulary. `subscribe`/`write`/`read`/`connect`/`disconnect`/`connectionState`/`scan`.
- **`android/.../ble/NordicBleTransport.kt`** — the real transport the relay taps. Note `peripherals`/`states` are `ConcurrentHashMap` (multi-device ready); the per-device subscribe flow is lossless/non-conflating (keep that property across the network).
- **`android/.../ble/De1BleManager.kt` / `ScaleBleManager.kt`** — sit on `BleTransport`; the proxy slots in beneath them untouched. (They now also own the M0 reconnect session loop — the proxy link will want the same backoff machinery one layer up.)
- **`android/.../ui/MainViewModel.kt`** — constructs `bleTransport` + the managers (~line 708); where a "primary vs remote" mode switch would live (pick `NordicBleTransport` vs `ProxyTransport`).
- **Web (M4, not now):** `web/.../ble/de1-transport.ts` is the analogous named seam (`De1Transport`) — a `WebSocketTransport` there is the PWA client. Don't build it in M1, but keep the protocol browser-friendly for it.

---

## Build / verify

- `cd android && ./gradlew :app:assembleDebug` (and `:app:compileDebugKotlin`).
- Emulators: `Pixel_10_Pro` (phone) + `Medium_Tablet` (tablet); seed fake data via `.scratch/android-compose-polish/seed/seed.sh emulator-5554`. adb at `~/Library/Android/sdk/platform-tools/adb`.
- **Testing wrinkle:** the relay needs a *primary with a real BLE link* — emulators have **no Bluetooth**. To test the proxy without hardware, back the "primary" with a **`BleSessionRecorder` replay** (the recorded-session capture the app already produces) feeding a fake transport, so a secondary emulator can connect over the LAN to a replayed primary. Real end-to-end (live DE1) is hardware-gated. Plan the protocol + ProxyTransport to be unit-testable against an in-process loopback first.

---

## Decisions still open (from RESEARCH §9 — resolve as you build)

- Protocol framing details (length-prefixed JSON vs binary; one socket per device vs multiplexed).
- Authority hand-back if the primary leaves BLE range (explicit-only vs auto-elect) — likely M3.
- Whether the relay reuses the BLE capture format on the wire (debuggability) or a leaner frame.

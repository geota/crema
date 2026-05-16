# Crema — Design & Research Docs

Feasibility analysis and design docs for **Crema**, a ground-up rewrite of the Decent
Espresso DE1 tablet app. Architecture is **locked**; this folder is the design record.

## Decision (locked 2026-05-16)

- **Rust `de1-core`** — sans-IO domain + protocol library. No Bluetooth, no UI, no file I/O.
  Consumes inbound bytes + timer ticks; emits a typed event/effect stream.
- **Native Android UI** — Kotlin + Jetpack Compose. Tablet primary, phone secondary.
- **FFI** — UniFFI-generated Kotlin bindings.
- **BLE transport** — native (Kotlin), as required by Android.
- **No plugin architecture.** The core's typed event/effect stream is its own API
  (for testability); no pub/sub bus, no event-schema versioning, no extension SDK.
- **License — GPL-3.0-or-later.** Matches the DE1 ecosystem (`de1app` and its plugins are
  GPL-3.0), so GPL code (incl. pyDE1) may be referenced and adapted freely.

## Documents

| # | Doc | Status |
|---|-----|--------|
| 01 | [Feasibility analysis](01-feasibility.md) | Accepted |
| 02 | [DE1 BLE protocol reference](02-ble-protocol.md) | Done — 6 ambiguities flagged |
| 03 | [pyDE1 & reference-impl evaluation](03-pyde1-evaluation.md) | Done — 3 web items unverified |
| 04 | [MVP scope](04-mvp-scope.md) | Draft 2 |
| 05 | [Extension / plugin architecture](05-plugin-architecture.md) | Shelved — no plugin architecture |

## Next: Phase 0 spikes

- **Spike A** — `de1-core` skeleton: implement the fixed-point codecs + `ShotSample` /
  `StateInfo` structs, with unit tests using legacy `binary.tcl` as a codec oracle. Pure
  Rust, no Android, no hardware.
- **Spike B** — Android BLE: throwaway Kotlin app that connects to the real DE1, enables
  notifications, and dumps raw bytes to logcat.
- **Exit criteria**: bytes captured by Spike B parse correctly in Spike A.

## Reference material

The legacy Tcl app is the de-facto protocol spec. Keep a checkout of `de1app` beside this
repo — paths below are relative to `../de1app/`.

- Highest-value files: `de1plus/binary.tcl`, `machine.tcl`, `de1_comms.tcl`, `de1_de1.tcl`,
  `bluetooth.tcl`, `vars.tcl`, plus `de1plus/documentation/`.
- No raw BLE captures exist: `de1plus/traces/` is empty; `de1plus/simulations/*.shot` are
  *decoded* shot records. Use `binary.tcl` as the codec oracle to generate test vectors.
- That checkout's git submodules (plugins, skins, `misc`, `psd`, profile editors) are not
  populated and `.gitmodules` is partially broken. The protocol/core work does not need
  them. Populate (`git submodule update --init`) only before porting a submodule-hosted
  feature — e.g. DYE journaling or the graphical profile editors.

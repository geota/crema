# 08 · FFI Bridges and the Web Target

Status: **decided 2026-05-16.** Supersedes the "Android-focused" framing in the
earlier docs — Crema now targets two shells.

## Targets

Two shells over one sans-IO Rust core:

| | Android | Web |
|---|---|---|
| Bridge | UniFFI → Kotlin | wasm-bindgen → JS/TS |
| Bridge crate | `de1-ffi` (`cdylib`) | `de1-wasm` (`cdylib`) |
| BLE transport | `BluetoothGatt` | Web Bluetooth API |
| UI | Jetpack Compose | web framework (TBD) |
| Build | cargo-ndk + uniffi-bindgen | wasm-pack |
| Packaging | APK | installable PWA |

The core compiles to `wasm32-unknown-unknown` **unchanged** — verified. The
sans-IO design made the web target free at the core layer.

## Crux — considered and rejected

Crux (Red Badger) is the closest prior art: a sans-IO Rust core with native
shells. It was evaluated and rejected:

- Crux's headline benefit is a **shared `ViewModel`** — every shell renders the
  view the core computes. Crema's UIs diverge per platform (web ≠ tablet ≠
  phone), which removes that benefit.
- Stripped of shared-view, Crux offers bridge plumbing (≈ what Option S below
  gives) and enforced MVU discipline (the core already practises it, being
  sans-IO with an events-in/commands-out shape).
- Crux does not eliminate the two build pipelines; its concrete saving is the
  ~60 lines of bridge delegation per platform.
- Crux would dictate the UI architecture; we want idiomatic native UIs.

Decision: keep Crux's *architecture* (sans-IO, events in / commands out), use
UniFFI + wasm-bindgen, do **not** adopt the framework.

## Crate structure — define the surface once

`#[uniffi::export]` and `#[wasm_bindgen]` are proc-macros that must annotate the
definition site, and cannot both apply to one type. So application logic lives
in a bridge-agnostic facade; the bridge crates are thin delegation shims.

```
core/
  de1-protocol, de1-scale, de1-domain   — unchanged
  de1-app    — the FFI-agnostic facade: CremaCore + the Event / CoreOutput
               (later: Command) value types. Plain, tested Rust, no FFI deps.
  de1-ffi    — UniFFI bridge (Android). Wraps de1-app behind a Mutex.
  de1-wasm   — wasm-bindgen bridge (Web). Wraps de1-app behind a Mutex.
```

`CremaCore` uses ordinary `&mut self` methods; the `Mutex` (needed for the
bridges' `&self` FFI objects) is a bridge-crate concern.

## The surface

A duplex sans-IO API — inputs in, observations (and later, commands) out:

```
CremaCore::new()
on_notification(source, bytes, now_ms) -> CoreOutput
on_tick(now_ms)                        -> CoreOutput
reset()

CoreOutput { events: Vec<Event> }   // + commands: Vec<Command>, when the core drives
```

Inputs are simple typed scalars/enums — both bridges marshal these natively.
The rich output crosses as JSON; see Option S.

## Bridge encoding — Option S (serialized)

`CoreOutput` serializes to JSON; each bridge method is a one-line delegation
returning the JSON string. Chosen over typed records (Option T) because, with
two targets, "typed" means doing the binding work twice in two different
mechanisms, whereas serialized makes both bridges trivial and the native types
are generated once per platform (via `typeshare`). The whole domain is already
`serde` — Option S is nearly free. JSON ser/de cost at the ~10 Hz telemetry rate
is negligible.

## Web Bluetooth — constraints

- Chromium only (Chrome / Edge / Opera) — not Firefox, not Safari, not iOS.
- Scans require a user gesture; no passive / background scanning.
- Foreground-only; fine for a ~30 s shot.
- The DE1 GATT (notify + write) is fully supported.
- "Installation" = PWA (manifest + service worker) — installable, offline-capable
  for everything except the live BLE link.

## Build sequence

1. `de1-app` facade — `CremaCore` + `Event` / `CoreOutput`, tested.
   **Cut 1 (done): DE1 shot observation.** Cuts 2+: scale weight, auto-stop,
   machine-driving `Command`s.
2. `de1-ffi` UniFFI bridge + a Kotlin smoke test (overlaps Spike B).
3. `de1-wasm` wasm-bindgen bridge + a JS smoke test.
4. `typeshare` wiring — generated Kotlin + TypeScript types from the Rust source.

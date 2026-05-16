# Crema

A ground-up rewrite of the Decent Espresso **DE1** tablet app — a native Android app with
a Rust core.

> Unofficial. Not affiliated with Decent Espresso. The official app is `de1app`.

## Why

The official `de1app` is ~80–100k lines of Tcl/Tk running on a bundled AndroWish runtime,
with a 12k-line bespoke canvas UI framework. Crema rebuilds it as a native Android app on a
clean, fully testable core.

## Goals

Crema is a **clean-room reimplementation, not a port.** The legacy Tcl is referenced to
understand the DE1's *protocol and behaviour* (both apps are GPL-3.0, so this is free) —
but the architecture is designed fresh. Explicit goals:

- **Modern, idiomatic code** — idiomatic Rust and Kotlin on current toolchains (Compose,
  AndroidX, coroutines/Flow). No Tcl-isms carried across.
- **Type-driven design** — make illegal states unrepresentable: typed `MachineState`,
  `Profile`, `ShotSample`; errors as `Result`. No untyped global dict/array soup.
- **Sans-IO, testable core** — protocol and shot logic unit-tested with no machine
  attached. The legacy app cannot be tested at all without hardware.
- **Clear layered separation** — protocol / domain / FFI / transport / UI, each testable in
  isolation, with an explicit API between core and shell.
- **Explicit state machines** over implicit callback ordering.

Two disciplines keep "fresh architecture" safe:

1. **Behaviour is preserved precisely even though structure is not.** The DE1 hardware does
   not care about our architecture — bytes, timing, and state transitions must match the
   legacy app exactly (SAW lag compensation, auto-tare thresholds, fixed-point edge cases).
   Reject the old *structure* freely; respect the old *behaviour* exactly.
2. **Best practices include simplicity.** "Modern" means clean, typed, tested, idiomatic —
   not over-abstracted. No speculative layers or premature generality (cf. the dropped
   plugin bus). YAGNI is a best practice too.

## Architecture

- **`de1-core`** — Rust, **sans-IO**. Protocol codecs, shot state machine, profile model.
  No Bluetooth, no UI, no file I/O — driven by inbound bytes + timer ticks, emits a typed
  event/effect stream. Fully testable with no hardware.
- **Android app** — Kotlin + Jetpack Compose. BLE transport, UI, history, persistence.
  Tablet primary, phone secondary.
- **FFI** — UniFFI-generated Kotlin bindings.

No plugin architecture: the core's typed event stream *is* its API. See [`docs/`](docs/).

## Status

**Pre-Phase-0.** Architecture decided and documented. Next step: two de-risking spikes —
a Rust codec skeleton (`de1-core`) and an Android BLE connection test. See
[`docs/04-mvp-scope.md`](docs/04-mvp-scope.md) and [`docs/README.md`](docs/README.md).

## Reference

The legacy Tcl app is the de-facto protocol spec. Keep a checkout of `de1app` beside this
repo (`../de1app/`); paths in `docs/` written as `de1plus/...` are relative to it.

## License

Crema is licensed under the **GNU General Public License v3.0 or later** — see
[`LICENSE`](LICENSE). This matches the DE1 ecosystem (`de1app` and its plugins are
GPL-3.0), so GPL-licensed code may be referenced and adapted freely.

Copyright © 2026 Adrian Maceiras.

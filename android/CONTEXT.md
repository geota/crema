# Android

The native Android shell for Crema: a single Jetpack Compose `:app` module
(one APK, adaptive by window size class) over the Rust `core`. It scans,
connects, and subscribes to the DE1 (and a scale) over Bluetooth itself, then
hands every raw GATT notification to the core for decoding and renders the
result. It is **not** a WebView — it shares the core with the Web shell, not
the web UI. CRUD / sync / machine-control surfaces are still growing (see the
README); today it connects and renders a live readout.

## Language

### The FFI seam

**CremaBridge**:
The UniFFI-generated Kotlin handle over the Rust core (`de1-ffi` compiled to
`libde1_ffi.so`). Raw GATT bytes go in via `onNotification(source, data, nowMs)`;
a JSON **CoreOutput** string comes out. The decode lives in Rust, never in
Kotlin — Kotlin only marshals bytes and deserialises the result. The same role
the WASM bridge plays for Web.
_Avoid_: the decoder, the parser (those are the core's job, reached *through* the bridge).

**CoreOutput**:
The JSON envelope `CremaBridge` returns from a notification or tick — the
decoded `Event`s (machine state, shot phase, telemetry) plus any `Command`s the
core wants sent back to the machine. Deserialised with kotlinx.serialization
against the vendored **CremaCoreTypes**.
_Avoid_: the response, the result blob.

**CremaCoreTypes**:
`core/CremaCoreTypes.kt` — the `#[typeshare]`-generated type definitions
(`Event`, `Command`, machine/scale shapes), **vendored** from
`core/bindings/crema-core.kt`. A vendored copy, not a hand-written one: it is
regenerated from the core, never edited in place.
_Avoid_: the models, the DTOs.

### BLE

**BleTransport**:
The Kotlin BLE abstraction (`NordicBleTransport` is the production impl over the
Nordic BLE library) that scans for a device by service UUID, connects, and
streams notifications. The bytes it surfaces are fed straight to **CremaBridge**
— the transport never interprets them.
_Avoid_: the GATT layer (that's an implementation detail beneath the transport).

**ScaleUuids / De1Uuids**:
The GATT service + characteristic UUIDs the BLE layer scans and subscribes for.
The DE1 and scale UUID sets are sourced from the core (the canonical owner) so
every shell agrees on what to scan for, rather than each hard-coding its own.
_Avoid_: the magic numbers, the hardcoded UUIDs.

## Example dialogue

> **Dev:** When a `ShotSample` notification arrives, where do we turn the bytes
> into pressure / flow numbers?
> **Domain expert:** Not in Kotlin — you hand the raw bytes to **CremaBridge**
> via `onNotification`, and it returns a **CoreOutput** with the decoded
> telemetry `Event`s. The Compose layer just renders them. Kotlin owning the
> decode would re-introduce exactly the cross-shell drift the FFI seam exists to
> prevent.
> **Dev:** And the UUIDs we scan for — those are ours to define?
> **Domain expert:** No, source them from the core (the same set the Web shell
> reads). The core is the canonical owner of **ScaleUuids** / **De1Uuids** so a
> new model's UUIDs land everywhere at once.

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

**Scale UUIDs (core-sourced) / De1Uuids**:
The GATT service + characteristic UUIDs the BLE layer subscribes / writes to.
**Scales are multi-vendor**, so the shell hardcodes none of theirs: the
connected scale's characteristics come from the core's `scaleUuids()` (the core
identifies the model + knows them), and the multi-scale pre-connect scan set
from `scale_scan_uuids()`. The **DE1** is a single fixed device, so **De1Uuids**
stays a small hardcoded shell map — there is only one DE1 GATT layout.
_Avoid_: hardcoding a scale's UUIDs in the shell (that's the cross-shell-drift
trap the FFI seam exists to prevent).

## Example dialogue

> **Dev:** When a `ShotSample` notification arrives, where do we turn the bytes
> into pressure / flow numbers?
> **Domain expert:** Not in Kotlin — you hand the raw bytes to **CremaBridge**
> via `onNotification`, and it returns a **CoreOutput** with the decoded
> telemetry `Event`s. The Compose layer just renders them. Kotlin owning the
> decode would re-introduce exactly the cross-shell drift the FFI seam exists to
> prevent.
> **Dev:** And a scale's GATT UUIDs — those are ours to hardcode?
> **Domain expert:** No — scales are multi-vendor, so the core owns them: it
> identifies the model and reports its characteristics via `scaleUuids()` (the
> same set the Web shell reads), and the generic `scale_scan_uuids()` is the
> pre-connect scan set. The shell hardcodes none. The DE1 is the exception — one
> fixed device, one GATT layout, so **De1Uuids** stays a small shell map.

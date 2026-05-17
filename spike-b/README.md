# Crema · Spike B

A **Phase-0 throwaway proof-of-concept** Android project. It is **not** the real
Crema app. Its only job is to prove the toolchain path end to end:

```
cargo-ndk  →  Android .so  →  UniFFI-generated Kotlin  →  Compose app  →  live BLE to a real DE1
```

If Spike B connects to a DE1 and shows machine state, shot phase, and telemetry
decoded **by the Rust core** (not by Kotlin), the FFI-to-real-BLE seam is
de-risked and the real Android shell can be built with confidence.

Phase-0 Spike A — the protocol parsing and sans-IO core — is already done. This
is Spike B: the integration spike.

---

## What it does

1. `de1-ffi` (the UniFFI bridge crate in `../core/`) is compiled to a native
   `libde1_ffi.so` for `arm64-v8a`.
2. UniFFI generates Kotlin bindings (`CremaBridge`, the input enums) from that
   `.so`.
3. A minimal `BluetoothGatt` BLE manager scans for a DE1 by its GATT service
   UUID, connects, and subscribes to the `StateInfo` and `ShotSample` notify
   characteristics.
4. Every BLE notification's raw bytes are passed to
   `CremaBridge.onNotification(source, data, nowMs)`. The bridge returns a
   **JSON `CoreOutput`** string.
5. The JSON is deserialized (kotlinx.serialization, types from
   `core/bindings/crema-core.kt`) and the decoded `Event`s are shown live in a
   Jetpack Compose screen.

It does **not** drive the machine, handle scales, reconnect, or persist
anything. Those are out of scope for a toolchain spike.

---

## Target hardware

- **Teclast P25T tablet, Android 12 (API 31)** — `minSdk 31`, `targetSdk 36`.
- A real **Decent Espresso DE1** machine, powered on and in BLE range.
- Android 12+ uses the `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` runtime
  permissions (not the legacy `BLUETOOTH` / location permissions); the app
  requests them at runtime.

---

## Prerequisites

| Tool | Notes |
|---|---|
| **Android Studio** (Ladybug or newer) or the Android command-line tools | Provides the SDK + an APK install path |
| **Android SDK** API 36 | `compileSdk` / `targetSdk` |
| **Android NDK** r26+ | Install via the SDK Manager ("NDK (Side by side)"). Note the version. |
| **JDK 17** | Required by Android Gradle Plugin 8.7 |
| **Rust** stable (≥ 1.95, edition 2024 — see `core/rust-toolchain`) | `rustup` |
| **Android Rust target** | `rustup target add aarch64-linux-android` |
| **Gradle 8.11+** | Only needed once, to generate the wrapper — see below |

### One-time setup

```sh
# Rust target for arm64 Android
rustup target add aarch64-linux-android

# Point Gradle at the SDK + NDK. Create spike-b/local.properties:
#   sdk.dir=/Users/<you>/Library/Android/sdk
#   ndk.dir=/Users/<you>/Library/Android/sdk/ndk/<version>
# (local.properties is git-ignored; Android Studio writes sdk.dir for you.)
```

The `org.mozilla.rust-android-gradle` plugin shells out to `cargo` with the NDK
linker; it locates the NDK from `ndk.dir` / `ANDROID_NDK_HOME` / the SDK's
`ndk/` directory. `cargo-ndk` as a separate CLI is **not** required when using
the plugin (it is the plugin's job).

---

## The Gradle wrapper

The Gradle **wrapper JAR is binary** and is intentionally **not committed** in
this scaffold. Generate it once, locally, before the first build:

```sh
cd spike-b
gradle wrapper --gradle-version 8.11.1
```

This produces `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar`.
After that, use `./gradlew` for everything. (`gradle-wrapper.properties` *is*
committed, so the wrapper version is already pinned.)

If you open the project in Android Studio, it offers to generate the wrapper
for you.

---

## Build & run

With a DE1 powered on and the tablet connected over ADB:

```sh
cd spike-b
./gradlew :app:installDebug
```

This runs, in order:

1. **`cargoBuild`** — the rust-android plugin compiles `de1-ffi` to
   `libde1_ffi.so` for `arm64-v8a` and stages it in the APK's `jniLibs`.
   (Configured in `app/build.gradle.kts`, `cargo { … }` block.)
2. **`generateUniffiBindings`** — runs `de1-ffi`'s bundled `uniffi-bindgen`
   binary against the freshly built `.so` and writes the Kotlin bindings to
   `app/build/generated/uniffi/coffee/crema/core/de1_ffi.kt`. That directory is
   on the `main` source set, so the bindings compile as ordinary Kotlin.
3. The Kotlin/Compose app compiles and links against the bindings; the `.so`
   is packaged into the APK.

Then launch the app on the tablet and tap **Connect to DE1**. Grant the
Bluetooth permissions when prompted.

### Manual fallback (no rust-android plugin)

If the Gradle plugin misbehaves, the same two steps can be done by hand:

```sh
# 1. Build the .so (requires `cargo install cargo-ndk`)
cd core
cargo ndk --target aarch64-linux-android --platform 31 \
    --output-dir ../spike-b/app/src/main/jniLibs \
    -- build --package de1-ffi --release

# 2. Generate the Kotlin bindings
cargo run --package de1-ffi --bin uniffi-bindgen -- generate \
    --library target/aarch64-linux-android/release/libde1_ffi.so \
    --language kotlin \
    --out-dir ../spike-b/app/src/main/java
```

If you go this route, drop the `org.mozilla.rust-android-gradle` plugin and the
`cargo { }` / `generateUniffiBindings` blocks from `app/build.gradle.kts`, and
make sure the generated `.so` and `.kt` are not double-added.

---

## What success looks like

1. Tapping **Connect** → status moves `SCANNING → CONNECTING → DISCOVERING →
   SUBSCRIBING → READY`.
2. The **Machine state** field shows e.g. `Idle / Ready` or `Sleep / …` — this
   string was decoded by the Rust core from a 2-byte `StateInfo` packet.
3. Start a shot on the DE1. The **Shot phase** field moves through
   `Heating → Preinfusion → Pouring → Ending`, and the **Telemetry** line
   updates at ~4–10 Hz with pressure / flow / head-temp.
4. The **Decoded events** log shows `ShotStarted`, `ShotPhaseChanged`,
   `ShotCompleted`, etc.

If all four happen, the spike has proven the full path and the real Android
shell can proceed.

A `DecodeError` event in the log is *also* a success signal for the seam — it
means a malformed packet reached the core and the core surfaced it cleanly
rather than crashing.

---

## DE1 BLE UUIDs

From `docs/02-ble-protocol.md` §1. All share the base
`0000xxxx-0000-1000-8000-00805F9B34FB`. See `app/.../ble/De1Uuids.kt`.

| Role | 16-bit | Purpose |
|---|---|---|
| Service (`suuid`) | `A000` | DE1 GATT service — scan filter |
| `StateInfo` | `A00E` | 2-byte machine state + substate (notify) |
| `ShotSample` | `A00D` | ~4–10 Hz telemetry stream (notify) |
| `RequestedState` | `A002` | 1-byte machine-state write |
| `ShotSettings` | `A00B` | steam / hot-water settings |
| `WaterLevels` | `A011` | tank level (notify) |
| CCCD | `2902` | standard notify-enable descriptor |

---

## Project layout

```
spike-b/
  settings.gradle.kts            — Gradle project, includes :app
  build.gradle.kts               — root: plugin versions
  gradle.properties              — JVM args, AndroidX
  gradle/wrapper/
    gradle-wrapper.properties     — pinned Gradle 8.11.1 (jar generated locally)
  app/
    build.gradle.kts             — Android + Compose + Rust/UniFFI integration
    src/main/
      AndroidManifest.xml        — BLUETOOTH_SCAN / BLUETOOTH_CONNECT
      java/coffee/crema/spikeb/
        MainActivity.kt          — Compose UI: Connect button + live readout
        SpikeViewModel.kt        — owns CremaBridge, parses JSON CoreOutput
        CremaCoreTypes.kt        — copy of core/bindings/crema-core.kt (CoreOutput
                                   JSON types). Generated artifact, do not edit.
        ble/
          De1Uuids.kt            — DE1 GATT service + characteristic UUIDs
          De1BleManager.kt       — scan / connect / subscribe / feed the core
      res/values/                — strings, theme
```

The UniFFI-generated `de1_ffi.kt` is **not** checked in — it is regenerated
into `app/build/generated/uniffi/` on every build.

---

## Known limitations / risks (Spike B is throwaway)

- **GATT op serialisation** is a tiny hand-rolled queue. Real Android BLE needs
  a robust serialised command queue; the real app must not copy this.
- **No reconnection, bonding, or MTU negotiation.** The DE1's 19-byte
  `ShotSample` fits in the default 23-byte ATT MTU, so MTU is not an issue here.
- **`SpikeViewModel` mirrors BLE state by polling** the manager's `StateFlow`
  on each callback rather than collecting it in a coroutine — adequate for a
  spike, wrong for production.
- **`CremaCoreTypes.kt` is vendored.** The real app should consume
  `core/bindings/crema-core.kt` from a shared location, not copy it.
- **Scale support is absent.** `CremaBridge.connectScale` / `tareScale` exist
  but Spike B does not exercise them.
- The DE1 advertised-name filter is lenient (accepts unnamed advertisements)
  because the service-UUID scan filter already does the real narrowing.

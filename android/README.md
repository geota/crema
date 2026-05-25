# Crema Android

The **Crema Android app** — the native Android shell of the Crema project.

It **began** as the Phase-0 FFI/BLE proof-of-concept (originally "Spike B"),
whose only job was to prove the toolchain path end to end:

```
cargo-ndk  →  Android .so  →  UniFFI-generated Kotlin  →  Compose app  →  live BLE to a real DE1
```

That seam — raw GATT bytes decoded **by the Rust core** (not by Kotlin) into
machine state, shot phase, and telemetry — is now de-risked, and this project
has been promoted from a throwaway spike into the real app. The Phase-0 screen
(`Connect to DE1` + a live readout) is still here as the first working surface;
the architecture below records where it grows from.

---

## What it does today

1. `de1-ffi` (the UniFFI bridge crate in `../core/`) is compiled to a native
   `libde1_ffi.so` for `arm64-v8a`.
2. UniFFI generates Kotlin bindings (`CremaBridge`, the input enums) from that
   `.so`.
3. A `BluetoothGatt` BLE manager scans for a DE1 by its GATT service UUID,
   connects, and subscribes to the `StateInfo` and `ShotSample` notify
   characteristics.
4. Every BLE notification's raw bytes are passed to
   `CremaBridge.onNotification(source, data, nowMs)`. The bridge returns a
   **JSON `CoreOutput`** string.
5. The JSON is deserialized (kotlinx.serialization, types from
   `core/bindings/crema-core.kt`) and the decoded `Event`s are shown live in a
   Jetpack Compose screen.

It does **not** yet drive the machine, handle scales, reconnect, or persist
anything — those are the next steps, not omissions of principle.

---

## Structure

The app is **one `:app` module producing one APK** that runs on both phone and
tablet. There are **no** separate per-form-factor views, modules, or APKs.

Adaptive UI is achieved by **window size classes** — `WindowSizeClass` via
`currentWindowAdaptiveInfo()` — and **Material 3 Adaptive** scaffolds
(`NavigationSuiteScaffold`, `ListDetailPaneScaffold`). Each screen is **authored
once** and re-flows by size class:

- **Compact** (phone) — single pane, bottom navigation.
- **Medium / Expanded** (tablet) — navigation rail or drawer, list-detail panes
  shown side by side.

Primary target is the **Teclast P25T tablet** (Expanded / Medium); the **Pixel
phone** (Compact) is the secondary target. Both are arm64.

Package layout under `coffee.crema`:

```
coffee.crema/
  core/      — UniFFI-binding wrappers + the CoreOutput JSON model types
  ble/       — BluetoothGatt BLE manager + DE1 GATT UUIDs
  ui/        — Compose entry point, theme, and size-class-adaptive shared UI
  feature/…  — per-feature screens (added as features land; none yet)
```

The UniFFI-generated `de1_ffi.kt` lands in `coffee.crema.core` (set in
`de1-ffi/uniffi.toml`) and shares that package with the typeshare-generated
model types — the two are complementary and intentionally co-located.

---

## Target hardware

- **Teclast P25T tablet, Android 12 (API 31)** — primary. `minSdk 31`,
  `targetSdk 36`.
- **Pixel phone** — secondary (Compact size class).
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
| **JDK 17** | Required by Android Gradle Plugin 9.x and Gradle 9.x |
| **Rust** stable (≥ 1.95, edition 2024 — see `core/rust-toolchain`) | `rustup` |
| **Android Rust target** | `rustup target add aarch64-linux-android` |
| **Gradle 9.3+** | Only needed once, to generate the wrapper — see below |

The build toolchain is **Gradle 9.3.1 / Android Gradle Plugin 9.1.1 /
Kotlin 2.2.20**, and the Rust crate is built by the **Mullvad fork** of the
rust-android plugin (`net.mullvad.rust-android` 0.10.1). A first successful
on-device build (`./gradlew :app:installDebug`) validates this toolchain
migration end to end.

### One-time setup

```sh
rustup target add aarch64-linux-android   # Rust target for arm64 Android
```

Create `android/local.properties` — git-ignored, per-machine config:

```properties
# SDK location — Android Studio writes this for you.
sdk.dir=/Users/<you>/Library/Android/sdk

# Absolute paths to cargo / rustc / python. REQUIRED for IDE builds: a Gradle
# build launched from Android Studio does NOT inherit your shell PATH, so it
# cannot find rustup's ~/.cargo/bin or a pyenv `python`. The
# net.mullvad.rust-android plugin and the generateUniffiBindings task read
# these. A CLI `./gradlew` build — which has your shell PATH — can omit them.
rust.cargoCommand=/Users/<you>/.cargo/bin/cargo
rust.rustcCommand=/Users/<you>/.cargo/bin/rustc
rust.pythonCommand=/usr/bin/python3
```

The `net.mullvad.rust-android` plugin shells out to `cargo` (with the NDK
linker) and a Python linker-wrapper script. The NDK is located via the
`ndkVersion` pinned in `app/build.gradle.kts`; `cargo-ndk` as a separate CLI
is **not** required — the plugin does that job.

---

## The Gradle wrapper

The Gradle **wrapper JAR is binary** and is intentionally **not committed** in
this scaffold. Generate it once, locally, before the first build:

```sh
cd android
gradle wrapper --gradle-version 9.3.1
```

This produces `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar`.
After that, use `./gradlew` for everything. (`gradle-wrapper.properties` *is*
committed, so the wrapper version is already pinned.)

If you open the project in Android Studio, it offers to generate the wrapper
for you.

---

## Build & run

With a DE1 powered on and the tablet (or phone) connected over ADB:

```sh
cd android
./gradlew :app:installDebug
```

This runs, in order:

1. **`cargoBuild`** — the `net.mullvad.rust-android` plugin compiles `de1-ffi`
   to `libde1_ffi.so` for `arm64-v8a` and writes it to
   `app/build/rustJniLibs/android/`. AGP's `merge*JniLibFolders` tasks depend
   on `cargoBuild` and pick that directory up so the `.so` ships in the APK.
   (Configured in `app/build.gradle.kts`, `cargo { … }` block.)
2. **`generateUniffiBindings`** — runs `de1-ffi`'s bundled `uniffi-bindgen`
   binary against the freshly built `.so` and writes the Kotlin bindings to
   `app/build/generated/uniffi/coffee/crema/core/de1_ffi.kt`. That directory is
   registered on each variant's Kotlin sources via the AGP 9 Variant API
   (`addGeneratedSourceDirectory`), so the bindings compile as ordinary Kotlin.
3. The Kotlin/Compose app compiles and links against the bindings; the `.so`
   is packaged into the APK.

Then launch the app and tap **Connect to DE1**. Grant the Bluetooth permissions
when prompted.

### Manual fallback (no rust-android plugin)

If the Gradle plugin misbehaves, the same two steps can be done by hand:

```sh
# 1. Build the .so (requires `cargo install cargo-ndk`)
cd core
cargo ndk --target aarch64-linux-android --platform 31 \
    --output-dir ../android/app/src/main/jniLibs \
    -- build --package de1-ffi --release

# 2. Generate the Kotlin bindings
cargo run --package de1-ffi --bin uniffi-bindgen -- generate \
    --library target/aarch64-linux-android/release/libde1_ffi.so \
    --language kotlin \
    --out-dir ../android/app/src/main/java
```

If you go this route, drop the `net.mullvad.rust-android` plugin and the
`cargo { }` / `generateUniffiBindings` blocks from `app/build.gradle.kts`, and
make sure the generated `.so` and `.kt` are not double-added.

---

## What a healthy run looks like

1. Tapping **Connect** → status moves `SCANNING → CONNECTING → DISCOVERING →
   SUBSCRIBING → READY`.
2. The **Machine state** field shows e.g. `Idle / Ready` or `Sleep / …` — this
   string was decoded by the Rust core from a 2-byte `StateInfo` packet.
3. Start a shot on the DE1. The **Shot phase** field moves through
   `Heating → Preinfusion → Pouring → Ending`, and the **Telemetry** line
   updates at ~4–10 Hz with pressure / flow / head-temp.
4. The **Decoded events** log shows `ShotStarted`, `ShotPhaseChanged`,
   `ShotCompleted`, etc.

A `DecodeError` event in the log is **not** a failure of the seam — it means a
malformed packet reached the core and the core surfaced it cleanly rather than
crashing.

---

## DE1 BLE UUIDs

All share the base `0000xxxx-0000-1000-8000-00805F9B34FB`.
See `app/.../ble/De1Uuids.kt`.

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
android/
  settings.gradle.kts            — Gradle project, includes :app
  build.gradle.kts               — root: plugin versions
  gradle.properties              — JVM args, AndroidX
  gradle/wrapper/
    gradle-wrapper.properties     — pinned Gradle 9.3.1 (jar generated locally)
  app/
    build.gradle.kts             — Android + Compose + Rust/UniFFI integration
    src/main/
      AndroidManifest.xml        — BLUETOOTH_SCAN / BLUETOOTH_CONNECT
      java/coffee/crema/
        core/
          CremaCoreTypes.kt      — copy of core/bindings/crema-core.kt (CoreOutput
                                   JSON types). Generated artifact, do not edit.
        ble/
          De1Uuids.kt            — DE1 GATT service + characteristic UUIDs
          De1BleManager.kt       — scan / connect / subscribe / feed the core
        ui/
          MainActivity.kt        — Compose entry point: Connect button + readout
          MainViewModel.kt       — owns CremaBridge, parses JSON CoreOutput
      res/values/                — strings, theme
```

The UniFFI-generated `de1_ffi.kt` is **not** checked in — it is regenerated
into `app/build/generated/uniffi/coffee/crema/core/` on every build.

---

## Known limitations / next steps

These are carryovers from the Phase-0 proof-of-concept that the real app must
address before it ships:

- **GATT op serialisation** is a tiny hand-rolled queue. A robust serialised
  command queue is needed before the BLE layer is production-grade.
- **No reconnection, bonding, or MTU negotiation.** The DE1's 19-byte
  `ShotSample` fits in the default 23-byte ATT MTU, so MTU is not urgent — but
  reconnection is.
- **`MainViewModel` mirrors BLE state by polling** the manager's `StateFlow` on
  each callback rather than collecting it in a coroutine — fine as a stopgap,
  to be replaced with proper collection.
- **`CremaCoreTypes.kt` is vendored.** It should be consumed from
  `core/bindings/crema-core.kt` via a shared location, not hand-synced.
- **Scale support is absent.** `CremaBridge.connectScale` / `tareScale` exist
  but are not yet exercised.
- The DE1 advertised-name filter is lenient (accepts unnamed advertisements)
  because the service-UUID scan filter already does the real narrowing.

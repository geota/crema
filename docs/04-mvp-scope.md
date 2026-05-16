# MVP Scope — Personal DE1 App

> Status: draft 3 — 2026-05-16
> Companion to `01-feasibility.md`. Defines the first shippable version: a scoped-down
> personal app, architected so it can grow.

---

## 1. Goal

A native Android app that lets **one owner** (you) pull a great espresso shot on a Decent DE1,
with a live graph and a connected scale — and nothing else. Architected cleanly (Rust core +
Compose shell) so steam/history/extensions can be added later without rework.

The success test for the MVP: **you stop opening the old Tcl app.**

---

## 2. Target devices

| | Decision |
|---|---|
| Primary | Android **tablet** — Teclast P25T, Android 12 (landscape, the machine-side device) |
| Secondary | Android **phone** — Pixel 10 Pro, Android 16 (portrait — "works and is usable", not pixel-polished) |
| One APK | Single build; responsive Compose layout via `WindowSizeClass` |
| ABIs | `arm64-v8a` (both devices) + `x86_64` (emulator) |
| `minSdk` | **API 31** (Android 12) — set by the Teclast. Convenient: API 31 is where Android moved BLE to runtime permissions (`BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`), so only the modern permission model is needed — no legacy `ACCESS_FINE_LOCATION` BLE path. |
| `targetSdk` | **API 36** (Android 16) — the Pixel's version. |
| 16 KB pages | Android 15+ requires native `.so` libraries to be 16 KB-aligned; the Pixel 10 Pro needs this. Build the Rust `.so` 16 KB-aligned (NDK r27+ default). |
| Perf baseline | The **Teclast P25T is a budget tablet** — it, not the Pixel, sets the performance bar (especially the 10 Hz shot graph). |
| Orientation | Locked landscape on tablet; portrait allowed on phone |

---

## 3. MVP user journey ("done" looks like this)

1. Open app → it auto-connects to the paired DE1 (and scale).
2. Machine state is visible; tap to wake it from sleep.
3. Pick a profile from a short list of imported profiles — or edit/create one in the
   form-based profile editor.
4. Place cup → app auto-tares the scale.
5. Start espresso (from the app, or the machine's GHC button).
6. Watch the **live shot graph** — pressure, flow, temperature, weight vs. time, with the
   profile's target lines overlaid.
7. Shot auto-stops at target weight (SAW) or volume (SAV), or manually.
8. Shot is saved locally; you can reopen and view its graph afterwards.
9. Use steam / hot water / flush with simple start-stop controls.

---

## 4. In scope

Each item below is MVP-required. "AC" = acceptance check.

### Connectivity
- **BLE scan + connect + bond** to the DE1. AC: survives app restart without re-pairing.
- **Auto-reconnect** on drop. AC: walking away and back re-establishes within ~10 s.
- **Bookoo scale** — connect, weight stream, tare, timer. (Advertises as `BOOKOO_SC*`;
  weight notifications on one characteristic, a separate command characteristic for
  tare/timer.) AC: weight tracks within scale resolution; tare zeroes within 1 s.
- **Foreground service** holding the BLE connection during a shot. AC: a shot completes
  cleanly with the screen off or the app backgrounded.

### Machine control & telemetry
- Stream **state / substate** and **ShotSample** telemetry.
- **Wake / sleep** the machine.
- Read essential **MMR** values: firmware version, water level (low-water warning).
- **Espresso**: load a profile's frames onto the machine and run a shot through the full
  frame state machine (preinfusion → pour → ending).
- **Steam**, **hot water**, **flush**: start/stop with current settings. (No steam-profile
  editing — use machine defaults.)

### Profiles
- **Run** a profile (the frame upload + execution path).
- **Import** profiles from the legacy app: JSON v2 format required; legacy Tcl-dict format
  best-effort. AC: at least 3 of your real profiles import and run identically.
- A simple **profile picker**.
- **Edit profiles — form / frame-table editor.** Add, remove, reorder, and duplicate
  frames; edit each frame's fields (pump type pressure/flow, target value, temperature,
  transition fast/smooth, duration, volume limit, exit conditions); edit profile-level
  metadata (title, notes, target weight/volume). Save back to JSON v2 and run.
  AC: a profile created/edited in-app uploads to the machine and runs correctly; round-trips
  through import/export without data loss.
  - **MVP editor is form-based, not a graphical curve editor.** Dragging points on a
    pressure/flow curve (as legacy `D_Flow` / `A_Flow` do) is deferred — see §5.

### Shot experience
- **Live shot graph**, ~10 Hz, multi-series (pressure, flow, temp, weight) with profile
  target overlays. AC: no visible jank on the Teclast P25T (the perf baseline) for a 40 s shot.
- **SAW** (stop-at-weight) and **SAV** (stop-at-volume), driven by the core. AC: shots stop
  within tolerance of target.
- Manual stop always available.

### History
- **Save** each completed shot locally (telemetry series + profile + metadata).
- **Browse** past shots and re-view their graphs. No analytics, no notes editor.

### Settings
- Paired DE1 + scale selection; target weight / volume; units; idle/sleep timeout.

### Non-functional
- **Lifecycle-safe**: rotation / config-change / process-death must not interrupt or
  corrupt an in-progress shot. State lives in the core + a `ViewModel`.
- **One opinionated visual design.** Design tokens, light/dark. No skin engine.
- Strings **externalized** for future i18n (no translations shipped).
- Crash-free shot path: a Rust panic must surface as a logged error, never a silent abort
  (panic hook → logcat; core returns `Result`).

---

## 5. Explicitly out of scope (deferred)

| Deferred | Rationale | Earliest |
|---|---|---|
| **Graphical curve** profile editor | Drag-points-on-a-curve UI (legacy `D_Flow`/`A_Flow`); large UI surface. The form/frame-table editor is in MVP scope | Phase 3+ |
| **Firmware update** over BLE | Risky to get wrong; keep the old app for this | Later, if ever |
| **Visualizer.com** upload | Nice-to-have, not core to pulling a shot | Phase 4 |
| 11 of 12 **scale** protocols | Only your scale matters for a personal app | On demand |
| The 49-skin **theming engine** | Replaced by one opinionated design | Not planned |
| **Plugin runtime** | See `05-plugin-architecture.md` — design the seam, defer the mechanism | Phase 4 |
| **i18n** translations | Externalize strings now; translate later | Later |
| Descale / clean / **maintenance** routines | Infrequent; old app suffices meanwhile | Phase 3+ |
| Cloud profile **sync**, multi-machine | Single owner, single machine | Not planned |
| Windows / macOS / **iOS** shells | Android-focused; core stays portable if this changes | Out |

---

## 6. Project structure

```
crema/                       (the repo at ~/code/crema)
├─ core/                      Rust workspace — the de1-core crate(s)
│  ├─ de1-protocol/           packet codecs, fixed-point formats, MMR
│  ├─ de1-domain/             profile model, shot state machine, history model
│  ├─ de1-scale/              scale protocol codecs
│  └─ de1-ffi/                UniFFI surface: exported API + event/command types
└─ android/                   Gradle project
   ├─ :core-bindings          generated Kotlin (UniFFI) + the .so, packaged
   ├─ :ble                    BluetoothGatt transport, scan/connect/notify
   ├─ :app                    Compose UI, ViewModels, foreground service
   └─ :data                   local persistence (profiles, history, settings)
```

The core is split so `de1-protocol` and `de1-domain` are pure and host-testable; only
`de1-ffi` knows UniFFI exists.

---

## 7. MVP acceptance criteria (ship-to-yourself gate)

1. App connects to your DE1 and scale automatically on launch.
2. You can run ≥3 of your real profiles, end to end, with results matching the old app.
3. The live graph is readable and jank-free for a full shot.
4. SAW stops shots at your target weight within tolerance.
5. A shot survives screen-off / backgrounding without interruption.
6. Completed shots are saved and re-viewable.
7. Steam, hot water, and flush all work.
8. You have used it as your only DE1 app for one full week without reaching for the old one.

---

## 8. Dependencies on research beads

- **`02-ble-protocol.md`** — required before Phase 1 core work; the **Bookoo** scale codec
  still needs a focused extraction from `bluetooth.tcl` / `vars.tcl`.
- **Shot-graph rendering approach** (Compose Canvas vs. Vico) — needed for Phase 2.

Resolved: license is **GPL-3.0-or-later** (matches `de1app`); `minSdk` API 31 /
`targetSdk` 36 (§2); MVP scale is Bookoo (§4).

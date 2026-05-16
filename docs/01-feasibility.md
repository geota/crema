# DE1APP Rewrite — Feasibility Analysis

> Status: **ACCEPTED** — 2026-05-16. Architecture decision locked.
> Scope of this document: decide *whether* to rewrite, *what architecture*, and *what to build first*.
> It is deliberately opinionated. Open questions are collected at the end as research "beads".

> ### DECISION LOCKED — 2026-05-16
> - **Rust `de1-core`** — sans-IO domain + protocol library. No Bluetooth, no UI, no file I/O.
> - **Native Android UI** — Kotlin + Jetpack Compose. Tablet primary, phone secondary.
> - **FFI** — UniFFI-generated Kotlin bindings.
> - **BLE transport** — native (Kotlin), as required by Android.
>
> Follow-on docs live in `rewrite/` — see `rewrite/README.md`.

---

## 1. TL;DR / Recommendation

**Rewrite is feasible and the architecture you already sketched is the right one.**

Build a **transport-agnostic Rust core library** (`de1-core`) holding all protocol and domain
logic, and glue it to a **native Android UI** (Kotlin + Jetpack Compose). The Rust core is
"sans-IO": it never touches Bluetooth, files, or the screen — it consumes inbound bytes and
timer ticks, and emits outbound byte-writes and domain events. Bluetooth and UI live in the
native shell.

This plays directly to your strengths (Rust backend systems), gives you a fully unit-testable
core with no device attached, and keeps the door open to a SwiftUI shell later without
re-implementing a single line of protocol logic.

**Do not start with the UI.** Start with two de-risking spikes (Section 9, Phase 0): the Rust
protocol core, and a throwaway Android BLE spike that connects to the real machine. Those are
the only two genuine unknowns. Everything else is "just work".

**Flutter**: viable as the UI shell *over the same Rust core*, but it conflicts with your stated
preference for best-in-class native UI, and it does not remove the hardest risk (Android BLE).
Recommendation: keep Rust core as the fixed point, treat the UI shell as a late, reversible
decision, and default to Compose. See Section 7.

---

## 2. What the current app actually is

A snapshot from this repo (`de1app`, commit `74cacdcd`, firmware-bundle v1352):

| Dimension | Reality |
|---|---|
| Language | Tcl/Tk — 198 `.tcl` files, ~80–100k LOC |
| Android runtime | **AndroWish/undroidwish** — Tk-on-SDL. The `apk/AndroWish64-debug.apk` (57 MB) *is* the runtime; the app ships as Tcl source on top of it |
| UI toolkit | **DUI** — a bespoke 12,710-line UI framework (`dui.tcl`) drawing onto a single Tk canvas. Plus `gui.tcl` (3,566 lines) and `vars.tcl` (4,386 lines) |
| Platform glue | `borg` API (AndroWish's Android bridge): sensors, toast, TTS, screen orientation, brightness, permissions, BLE |
| Repo size | 2.0 GB — almost entirely PNG/JPG/PSD skin assets, not code |
| Skins | 49 (most are pure cosmetic re-themes) |
| Plugins | 24 — Tcl namespaces registering callbacks on an event bus |
| Profiles | JSON v2 + legacy Tcl-dict format; a profile is an ordered list of "frames" (steps) with pressure/flow/temp targets and exit conditions |
| Cross-platform | Same Tcl source runs on Windows/macOS/Linux via undroidwish — *this is why upstream chose Tcl, and a constraint you have explicitly dropped* |

### The four real subsystems (everything else is UI chrome)

1. **DE1 BLE protocol** — `binary.tcl` (1,781 lines), `de1_comms.tcl`, `de1_de1.tcl`,
   `machine.tcl`. A GATT service with ~18 characteristics (`A001`–`A012` + MMR). Binary
   packets using fixed-point encodings (`U8P4`, `U16P8`, `S32P16`, `F8_1_7`, `U10P0`, …).
   Memory-Mapped Registers (MMR) read/written via a "maprequest" characteristic.
2. **Shot state machine** — documented in `documentation/de1_app_core_shot_cycle_overview.md`.
   States Idle/Espresso/Steam/HotWater/Flush; substates heating → stabilising → preinfusion →
   pouring → ending; SAW (stop-at-weight) and SAV (stop-at-volume); an event bus that fans
   state/flow/shot-sample changes out to GUI and plugins.
3. **Scales** — ~12 separate BLE scale protocols (Skale II, Decent Scale, Felicita, Bookoo,
   Acaia Pyxis, Atomheart Eclair, Eureka Precisa, Difluid, Smartchef, Solo Barista, Hiroia
   Jimmy, Varia Aku). Each is a small, independent codec.
4. **Persistence + cloud** — profiles, `.shot` history files, HTTP/TLS for the in-app updater
   and Visualizer.com upload.

**This existing code is your spec.** Keep the old repo as a read-only reference. `binary.tcl`,
`machine.tcl`, `de1_comms.tcl` and the shot-cycle doc are a complete, battle-tested description
of the protocol — that is what makes this rewrite a *port*, not a *reverse-engineering project*.

---

## 3. Risk assessment

| Item | Risk | Why / mitigation |
|---|---|---|
| DE1 BLE **protocol logic** | **Low–Medium** | Fully specified in `binary.tcl` + Decent's public GATT docs + the pyDE1 reference (Section 8). Fixed-point codecs are fiddly but mechanical. Ideal Rust work; 100% unit-testable. |
| **Android BLE transport** | **Medium–High** | Android's `BluetoothGatt` is notoriously quirky (connect/bond/MTU/threading races). This is the #1 real risk and exists in *any* architecture. Mitigate with Phase-0 spike + a proven library (Nordic Kotlin-BLE-Library). |
| Shot state machine | **Low–Medium** | Logic-heavy but well-documented. Port to Rust as a pure state machine driven by inbound events + ticks. |
| Real-time shot **graph** | **Medium** | The one genuinely non-trivial UI component: ~10 Hz multi-series live chart. Solvable in Compose (Canvas, or Vico). |
| Scales | **Low (per scale)** | Long tail of codecs, but each is small and isolated. MVP supports only the scale you own. |
| Data migration | **Low** | Read-only importer for legacy profiles/`.shot` files. |
| Firmware update over BLE | **Medium**, deferrable | Flashing firmware is risky to get wrong. Out of MVP scope — keep using the old app for that initially. |
| Skins / theming | **Low** | You have decided to be opinionated. One design, design tokens, no 49-skin engine. |
| Plugin ecosystem | **Medium**, deferrable | Existing plugins are in-process Tcl — not portable. A new extension model is a design task, not a port. See Section 6. |

No item is a showstopper. The protocol is documented; the hard part (Android BLE) is hard
*regardless* of language choice, so it cannot be designed away — only de-risked early.

---

## 4. Architecture options

### Option A — Single native Kotlin app (no shared core)
All protocol + domain + UI in Kotlin/Compose.
- ➕ Simplest toolchain; one language; best Android BLE ergonomics.
- ➖ Protocol/domain logic is now Android-bound. An iOS shell later = a full second
  implementation. Throws away your Rust strength.

### Option B — Flutter (Dart), one codebase
- ➕ Android + iOS + desktop from one UI codebase; good hot-reload DX.
- ➖ This is the "write once, run everywhere" model you said you *don't* want. Non-native
  feel; BLE still goes through a plugin (`flutter_blue_plus`) that wraps the same quirky
  platform APIs. Domain logic ends up in Dart unless you *also* add a Rust core — at which
  point you have two glue layers.

### Option C — Rust core + native UI shells  ⭐ recommended
`de1-core` (Rust, sans-IO) ⇄ thin FFI ⇄ Kotlin/Compose shell (and SwiftUI later).
- ➕ Domain logic written once, in Rust, fully testable with zero hardware.
- ➕ Each platform gets a genuinely native, best-in-class UI.
- ➕ Matches your skill set and your stated preference exactly.
- ➖ An FFI boundary to design and maintain; build tooling is more involved (cross-compiling
  Rust to Android ABIs).

### Option C′ — Rust core + Flutter UI
Same Rust core, Flutter as the *only* UI shell, bridged via `flutter_rust_bridge`.
- A legitimate hybrid: keep C's testable core, swap the native UI for one cross-platform UI.
- Sensible *if* you later decide one non-native UI for all platforms beats N native UIs.

**The key insight: the Rust core is the same in C and C′.** Whether the UI is Compose or
Flutter is a *reversible, deferrable* decision layered on top. So commit to the core now;
decide the UI shell after Phase 0.

---

## 5. Recommended architecture in detail

```
┌─────────────────────────────────────────────────────────┐
│  Android shell  (Kotlin)                                 │
│  ┌───────────────┐  ┌──────────────────────────────────┐ │
│  │ Compose UI    │  │ BLE transport                    │ │
│  │ - pages       │  │ - BluetoothGatt / Nordic lib     │ │
│  │ - shot graph  │  │ - scan / connect / notify        │ │
│  └──────┬────────┘  └───────────────┬──────────────────┘ │
│         │                           │                    │
│  ┌──────┴───────────────────────────┴──────────────────┐ │
│  │ Platform services: storage, permissions, TTS,       │ │
│  │ lifecycle, foreground service (keep BLE alive)       │ │
│  └──────────────────────┬──────────────────────────────┘ │
└─────────────────────────┼─────────────────────────────────┘
                          │  UniFFI-generated bindings
┌─────────────────────────┴─────────────────────────────────┐
│  de1-core  (Rust, no I/O, no async runtime needed)         │
│  - packet codecs (binary fixed-point ↔ typed structs)      │
│  - DE1 connection model + MMR registers                    │
│  - profile model + (de)serialization (v2 JSON + legacy)    │
│  - shot state machine (SAW/SAV, frames, timers)            │
│  - scale protocol codecs                                    │
│  - history model                                           │
│  - event bus (domain events out)                           │
└────────────────────────────────────────────────────────────┘
```

### The sans-IO contract (the heart of the design)

`de1-core` exposes something close to:

```rust
// Inbound — the shell feeds the core
core.on_de1_notify(char_uuid, &bytes);   // raw GATT notification
core.on_scale_notify(char_uuid, &bytes);
core.on_tick(now_ms);                     // drives timers, SAW/SAV, timeouts
core.load_profile(profile);
core.request_state(MachineState::Espresso);

// Outbound — the core tells the shell what to do, via events
enum CoreEvent {
    WriteCharacteristic { uuid, bytes },  // shell performs the GATT write
    StateChanged(MachineState, SubState),
    ShotSample(ShotSample),               // ~4–10 Hz, drives the live graph
    ShotComplete(ShotRecord),
    ScaleWeight { grams, flow },
    Notify(UserMessage),                  // toast / TTS hook
}
```

The core never knows what "Bluetooth" is — it only knows characteristic UUIDs and bytes.
That makes the entire protocol + shot logic testable by replaying byte streams.

> **Test data caveat:** the repo has **no raw BLE captures**. `de1plus/traces/` is an empty
> user drop-folder; `de1plus/simulations/*.shot` are *decoded* shot records (float
> time-series), useful as `ShotRecord`-shape references and plausibility checks but not as
> packet-decode tests. Build packet test vectors by using legacy `binary.tcl` as an
> **oracle** — it both encodes (`make_packed_shot_sample`) and decodes — and supplement with
> raw bytes captured in the Phase 0 BLE spike.

### FFI boundary

Recommend **UniFFI** (Mozilla; used in production by Firefox mobile). You write the Rust,
annotate it, and it generates idiomatic Kotlin (and Swift). Avoids hand-written JNI.
Evaluate `flutter_rust_bridge` only if/when a Flutter shell is chosen. → research bead.

### Why BLE stays in the native shell

Cross-platform Rust BLE (`btleplug`) has weak/no Android support — Android BLE *must* go
through the Android SDK via JNI. So BLE transport is native by necessity, not just by
preference. The core's UUID/bytes contract makes this a clean seam, not a compromise.

### Concurrency

The core can be a plain synchronous state machine behind a `Mutex` — BLE events arrive
serially and slowly (Hz, not MHz). No async runtime needed inside the core. The shell owns
threading. This keeps the FFI surface tiny and the core trivial to reason about.

---

## 6. Extensibility & the "plugin" question

The current plugin model = in-process Tcl callbacks on an event bus. Powerful, unsandboxed,
and **not portable** — none of the 24 plugins survive a rewrite as-is.

Phased approach:

- **MVP**: no plugin system. Just architect the core around a clean **event bus** and
  trait-based **extension points** (e.g. `ShotObserver`, `ProfileSource`). "Plugins" are
  compiled-in Rust modules. This is enough to keep the codebase modular without committing
  to a runtime extension API.
- **Later**: a real extension boundary. Options to evaluate (research bead):
  - **WASM components** loaded by the Rust core via `wasmtime` — sandboxed, language-agnostic,
    matches the "core owns the contract" design.
  - An embedded scripting runtime (`rhai`, or `mlua`) — easier for casual contributors.
  - Native Kotlin extension modules — simplest, but Android-only.

Defer the decision. Don't let a hypothetical plugin API slow the MVP. → research bead.

---

## 7. Native (Compose) vs Flutter — the UI shell decision

Decide *after* Phase 0, when you've felt the BLE work first-hand. Framing:

| | Compose shell (Option C) | Flutter shell (Option C′) |
|---|---|---|
| Rust core | identical | identical |
| Android UI feel | best-in-class native | good, not native |
| iOS later | separate SwiftUI shell (UI re-done, core reused) | same Flutter UI reused |
| BLE | Nordic Kotlin-BLE-Library (mature) | `flutter_blue_plus` (decent, thinner) |
| Your stated preference | ✅ matches "native UI + shared lib" | ✗ closer to "write once" |
| Live shot graph | Compose Canvas / Vico | `fl_chart` / custom painter |

Given your explicit preference and that iOS/desktop are *not* priorities for you, **default
to Compose**. Revisit only if multi-platform UI becomes a real goal — and even then, the core
investment is already protected.

---

## 8. Key external reference: pyDE1

Before writing protocol code, evaluate **pyDE1** — a clean, well-architected Python
implementation of the DE1 protocol and shot logic, by the same author as this repo's
shot-cycle documentation. It is *not* Tcl, *not* DUI-encumbered, and is structured roughly
the way `de1-core` should be. It is likely your single most valuable porting reference
alongside `binary.tcl`.

Also confirm the current state of Decent's **publicly published GATT/firmware spec**. Between
the spec, pyDE1, and `binary.tcl` you have three independent sources — enough to port with
confidence and cross-check. → research bead.

---

## 9. Phased roadmap

### Phase 0 — De-risk (do this before committing further)
- **Spike A — Rust core skeleton**: `de1-core` crate; implement the fixed-point codecs and
  parse one real `ShotSample`/state packet from `de1plus/traces/`. Pure Rust, no Android.
- **Spike B — Android BLE**: throwaway Kotlin app that scans, connects to the real DE1,
  enables notifications, and dumps raw bytes to logcat.
- **Exit criteria**: bytes from Spike B parse correctly in Spike A. If both work, the project
  is green-lit and the two unknowns are gone.

### Phase 1 — Core protocol complete
Full packet codecs, MMR, profile model (v2 JSON + legacy importer), shot state machine,
your scale's codec. Headless tests driven by `binary.tcl`-oracle test vectors plus bytes
captured in Spike B. No UI.

### Phase 2 — Minimal Compose UI (espresso only)
Connect/pair, load a profile, run an espresso shot, live shot graph, SAW/SAV. The MVP.

### Phase 3 — Round out the personal app
History save/view, steam / hot water / flush controls, settings, one design theme.

### Phase 4 — Share & extend
Polish for community release, Visualizer upload, decide the extension model (Section 6).

### MVP feature scope (the "scoped-down personal app")

| In MVP | Deferred / dropped |
|---|---|
| BLE connect + pair to DE1 | Firmware update over BLE |
| Live state + ShotSample stream | The 49-skin engine (→ one opinionated design) |
| Load + run an espresso profile | In-app profile *editor* (hand-edit JSON / import first) |
| Live shot graph (P/F/T/weight) | 11 of 12 scale protocols |
| SAW / SAV | The 24 Tcl plugins |
| One scale (yours) | Visualizer upload (→ Phase 4) |
| Steam / hot water / flush | Windows/macOS/iOS shells |
| Shot history save + view | Cloud profile sync |
| Legacy profile/history import | Multi-language i18n (keep strings externalizable) |

---

## 10. Effort — honest framing

This is a **multi-month part-time project**, not a weekend. Calibration, not a quote:

- The Rust core is the *smaller* and *more enjoyable* half for you — protocol is documented,
  logic is testable, no platform pain. Weeks, not months, of focused work.
- The Android shell — BLE reliability, lifecycle, foreground service, and especially the
  polished real-time UI — is the long pole. Expect it to dominate the timeline.
- You are deleting ~12k lines of DUI and 49 skins on day one. The thing you "hate" is mostly
  the UI layer, and that is exactly the layer you get to design fresh.

The risk profile is favorable: a documented protocol, a reference implementation (pyDE1),
recorded test traces in-repo, and an architecture matched to your strengths.

---

## 11. Open research questions ("beads")

Each of these is a candidate for its own follow-up research doc (`rewrite/0N-*.md`):

1. **BLE spec verification** — reconcile Decent's published GATT spec, pyDE1, and
   `binary.tcl`. Produce a canonical characteristic + packet reference.
2. **pyDE1 evaluation** — how close is its structure to the target `de1-core`? License?
   Worth porting vs. referencing?
3. **FFI tooling** — UniFFI vs. `flutter_rust_bridge` vs. hand-written JNI; build pipeline
   for cross-compiling Rust to Android ABIs (`cargo-ndk`).
4. **Android BLE library** — raw `BluetoothGatt` vs. Nordic Kotlin-BLE-Library; reliability,
   bonding, reconnection.
5. **Shot graph rendering** — Compose Canvas vs. Vico vs. custom; can it sustain ~10 Hz.
6. **Extension/plugin architecture** — WASM (`wasmtime`) vs. scripting (`rhai`/`mlua`) vs.
   native modules.
7. **Visual design direction** — the single opinionated theme; design tokens.
8. **Scale support strategy** — which scale(s) for MVP; codec abstraction shape.
9. **Visualizer.com API** — upload format, auth, where it sits in the architecture.
10. **Firmware update** — feasibility and safety of doing BLE firmware flashing in-app.

---

## 12. Decision summary

| Question | Answer |
|---|---|
| Rewrite feasible? | **Yes** — it's a port of a documented protocol, not a reverse-engineering effort. |
| Architecture? | **Rust `de1-core` (sans-IO) + native Kotlin/Compose shell.** |
| Native vs Flutter? | Core is fixed; **default to Compose**, decide UI shell after Phase 0. |
| Where does BLE live? | **Native shell** — required by Android; clean UUID/bytes seam to the core. |
| Plugins? | **Deferred.** Build a clean event bus now; design the extension boundary later. |
| First step? | **Phase 0 spikes** — Rust codec + Android BLE — *not* the UI. |

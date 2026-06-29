# Changelog

All notable changes to Crema are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and Crema aims to follow [Semantic Versioning](https://semver.org/).

## [0.0.1] — 2026-06-29

Initial release. Crema is an open-source (GPL-3.0) companion app for the
[Decent Espresso DE1](https://decentespresso.com/) — a clean-room reimplementation
of the DE1 tablet experience as a fast, type-safe web PWA, with a parallel native
Android app. Both shells share one sans-IO Rust core for the Bluetooth protocol,
shot state machine, and domain model.

### Added

#### Brewing & machine control
- **Live brew dashboard** — real-time pressure / flow / temperature / weight
  telemetry, a multi-channel chart whose time axis auto-grows with the shot, a
  phase indicator, and shot-completion metrics (time, yield, ratio, peak pressure).
- **Quick Controls** — steam, hot water, and flush with configurable targets, plus
  auto-tare and stop-on-weight.
- **Profile library** — the 88 standard de1app profiles built in, plus create / edit
  custom multi-frame profiles and live-preview each profile's intended
  pressure/flow curve.
- **Group-head controller (GHC)** — surfaced read-only; Crema correctly defers to the
  firmware's group-head start gate rather than fighting it.

#### Data
- **Shot history** — every pour recorded locally with full telemetry curves, linked
  to beans and roasters, with multi-shot overlay comparison and round-trip
  community-v2 `.shot.json` import/export.
- **Bean & roaster library** — track bags, roast dates, and grinder settings, attach
  optional bean-bag photos, and retroactively rebind a shot to a bean with snapshot
  semantics.
- **Maintenance tracking** — water-filter, descale, and cleaning reminders with
  one-tap buttons that drive the DE1's built-in cycles.

#### Hardware
- **DE1 over Bluetooth** — connect, control, and stream telemetry via the DE1's
  public BLE GATT protocol, with the wire format verified against the de1app and
  reaprime reference implementations.
- **Bluetooth scales** — Bookoo Themis, Decent Scale, Acaia (Lunar / Pyxis / Pearl),
  Skale, Eureka Precisa, Hiroia Jimmy, Difluid, Felicita, Atomheart Eclair, Varia
  Aku, and Smartchef.

#### Sync & backup (all opt-in, local-first by default)
- **Visualizer** — OAuth 2.0 + PKCE sign-in and two-way sync of shots, beans, and
  roasters with last-write-wins conflict resolution.
- **Google Drive backup & restore** — whole-app backup (preferences, profiles, shot
  history, and the bean/roaster library including photos) to your own Drive,
  strictly user-initiated.

#### Platforms
- **Web PWA** — runs entirely in the browser, offline-capable, nothing to install;
  Bluetooth pairing needs a Chromium-based browser. Hosted at
  [crema.maceiras.dev](https://crema.maceiras.dev).
- **Android** — native Jetpack Compose app with dedicated tablet and phone layouts
  and background BLE, distributed via Google Play, IzzyOnDroid, and a nightly
  Obtainium train.
- **Shared Rust core** — protocol codecs, shot state machine, profile model, and sync
  logic compiled to WebAssembly (web) and exposed via UniFFI (Android), so both
  shells stay in lockstep.

### Notes
- Crema is **unofficial** and not affiliated with Decent Espresso.
- This is an early release, built with heavy LLM-assisted development, and provided
  **as is** with no warranty — see the [Terms](https://crema.maceiras.dev/terms).
  Take particular care with machine-control settings (mains voltage, calibration,
  firmware updates).

[0.0.1]: https://github.com/geota/crema/releases/tag/v0.0.1

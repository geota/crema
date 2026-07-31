# Changelog

All notable changes to Crema are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and Crema aims to follow [Semantic Versioning](https://semver.org/).

## [0.0.5] — 2026-07-31

### Fixed

#### Stop-at-weight reliability
- **Stop targets survive a DE1 reconnect** — the long-running "stop at weight is
  intermittent" report. A reconnect rebuilt the core and dropped every configured
  stop target; shots started at the group head never re-pushed them, so the shot
  ran with no stop of any kind and recorded an empty stop reason. Restarting the
  app "fixed" it, which is why it looked like a scale fault.
- **The Brew stop-conditions card reads the core's armed projection** instead of
  re-deriving from UI state, so it can no longer show a confident target the core
  never armed. A guard that cannot fire (no scale) is flagged inline, and "nothing
  will stop this shot" is stated rather than rendered as an empty card.
- **A manual tare mid-pour is refused** — taring during extraction corrupts
  stop-at-weight.
- **Last-shot diagnostics are frozen to disk** at shot completion and surfaced in
  Copy diagnostics, so a shot problem reported hours later still has its event
  trail. Tank level is logged only when it changes, which had been flooding the
  buffer at ~2.4 lines/s.

#### Water level
- Corrected for the sensor offset, shown in the user's units, and read against the
  machine's own refill point, with a configurable low-water warning in percent or
  millilitres.

#### Visualizer
- Dispensed water is uploaded at the ecosystem's 0.1x wire scale (it had been
  10x high), and the profile frame index is emitted as `state_change` so uploaded
  shots get their step bars.
- Per-shot upload and re-upload with confirmation toasts.

#### Machine
- The DE1 sleeps when Crema is quit, and its own user-presence sleep is armed.
- Google Drive sign-in reports a refused authorisation instead of hanging.
- Nightly builds no longer tell users on the latest nightly to update.

### Added

#### Service modes are visible
- Mode glyphs stay dimmed until the heater each mode draws on is up to
  temperature — one rule in the core, shared by all three shells, replacing a
  hardcoded 130 °C threshold that never lit for a 120 °C steam target.
- The temperature card retargets to steam or hot water while that mode runs,
  demoting rather than dropping the group reading.
- The Phase card carries the running mode's progress against the firmware
  timeout — it has no profile frames to show during a service mode anyway.

### Changed
- Tablet layout fixes for ~8" and wide 240 dpi displays, and the Visualizer
  upload action stays reachable when signed out.

[0.0.5]: https://github.com/geota/crema/releases/tag/v0.0.5

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

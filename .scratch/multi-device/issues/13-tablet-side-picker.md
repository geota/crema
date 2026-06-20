# 13 — Tablet-side multi-device picker

- **Status:** done (live picker ported to the tablet; debug selector kept as the escape hatch)
- **Severity:** P3
- **Area:** Android (tablet UI)
- **Depends on:** none (07 for the push action)

## Problem

Only the **phone** has the Spotify-style "Other devices" picker (in `PhoneDevicesSheet`).
The **tablet** only has the debug role selector in `SettingsScreen.kt` (restart-to-apply).
So you can't initiate Mirror / Take-over / (eventually) Hand-off from the tablet UI — but
the tablet is the *most likely* primary (it lives by the machine), so it's exactly the
device that should offer "hand off to my phone".

## Fix

- Port `MultiDeviceSection` / `MirrorRow` (and the live `switchToSecondary`/`switchToNormal`
  /`requestHandoff` wiring) into the tablet's Devices affordance — a tablet equivalent of
  `PhoneDevicesSheet` (or reuse the shared composables; they're already role-agnostic).
- Surface the same `ui.peers` (NSD) + debug manual peer + (with #07) the "Hand off to
  <secondary>" list when the tablet is the primary.
- Retire/relabel the debug restart-to-apply role selector once the live picker covers the
  tablet (keep it behind the debug panel as the escape hatch).

## Acceptance / Verify

From the tablet UI: Mirror a primary, Stop, and (with #01/#07) Take-over / Hand-off —
same live, no-restart behavior as the phone. The debug selector still works as the
escape hatch.

## Touched files

- `android/app/src/main/java/coffee/crema/ui/screens/SettingsScreen.kt` (or a new tablet Devices sheet)
- reuse `android/app/src/main/java/coffee/crema/ui/phone/PhoneBrewSheets.kt` `MultiDeviceSection`/`MirrorRow` (extract to shared if needed)
- `android/app/src/main/java/coffee/crema/ui/MainViewModel.kt` — (already exposes the verbs)

## Comments
<!-- triage + progress notes append below -->

**2026-06-20 — done + 2-emulator validated.**
- **Extracted** `MultiDeviceSection`/`MirrorRow` from `PhoneBrewSheets` into the
  shared `coffee.crema.ui.components.MultiDevicePicker` (public, role-agnostic).
  The phone's Devices sheet now uses the shared one (via its `components.*`
  wildcard); a `showHeader` flag lets the tablet suppress the internal "Other
  devices" header (its Settings group title serves instead). The "Mirroring" row
  now names the primary (`mirroringPrimaryName`, issue 10).
- **Tablet:** a live `SetGroup("Multi-device")` in Settings ▸ Advanced wires the
  shared section to `vm.switchToSecondary` / `switchToNormal` / `requestHandoff`
  — Mirror from / Stop / Take over, no restart. The old role selector is kept +
  relabeled "Multi-device — manual override (debug)" as the escape hatch (and,
  until a live "Host" action, the way to become a primary).

**Validation (phone primary replay / tablet secondary, cross-forwarded via
host:9099):** tablet Settings ▸ Advanced showed the live picker = "Mirroring
sdk_gphone16k_arm64 / Stop / Take over" while live-mirroring the phone's shot;
**Stop** flipped it to normal (no restart) → "Debug primary · Mirror"; **Mirror**
flipped it back to secondary (no restart) → "Mirroring / Stop". Role in the
relabeled debug group tracked Off↔Secondary live.

**Follow-up:** the picker offers Mirror/Stop/Take over but not a live "Host the
DE1 here" (become primary) — that still needs the restart-to-apply role selector;
a live `switchToPrimary` affordance is a small add. "Hand off to <secondary>"
(push) is #07.

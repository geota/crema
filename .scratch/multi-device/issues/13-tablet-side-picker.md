# 13 — Tablet-side multi-device picker

- **Status:** ready-for-agent
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

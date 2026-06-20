# 10 — App-wide "you're viewing a mirror" signaling

- **Status:** ready-for-agent
- **Severity:** P2
- **Area:** Android (phone UI)
- **Depends on:** none

## Problem

The only "you're a secondary" cue is the **"· Mirroring" badge on the Brew screen**
(plus the orange bluetooth icon + the Devices-sheet row). Wander into **Settings,
Profiles, Beans, or Scale** on a secondary and nothing signals that you're looking at
someone else's machine — which is exactly where you'd change something assuming it's
local. Config edits are relayed (T2-polish/#06), but the *machine* settings (Settings ▸
Machine/Service) and one-off reads are not, and the user has no indication of authority.

## Fix

- A persistent, app-wide authority affordance when `proxyRole == "secondary"`: e.g. a
  thin top banner / status-bar tint / a "Mirroring <primary name>" chip in the top app
  bar that's present on every tab, tappable → the Devices sheet (Stop / Take over).
- Pull the primary's display name from the `Welcome`/`Config` (add `primaryName` to
  `ConfigSnapshot` or surface it from the handshake) so the chip reads "Mirroring
  Kitchen tablet".
- Settings ▸ Machine/Service rows that aren't relayed should be **disabled** (or clearly
  "controlled by <primary>") on a secondary, so a mirror can't half-apply a machine
  setting that goes nowhere.

## Acceptance / Verify

On a secondary, every tab shows the mirroring affordance; machine-settings rows that
don't relay are disabled/annotated; tapping the chip opens the Devices sheet.

## Touched files

- `android/app/src/main/java/coffee/crema/ui/phone/*` — top-bar/banner affordance across tabs
- `android/app/src/main/java/coffee/crema/ui/MainViewModel.kt` / `ConfigSnapshot` — `primaryName`
- Settings rows — disable/annotate when secondary

## Comments
<!-- triage + progress notes append below -->

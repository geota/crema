# 10 — App-wide "you're viewing a mirror" signaling

- **Status:** done (app-wide banner + primaryName + disabled machine-settings on a secondary)
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

**2026-06-20 — done + 2-emulator validated.**
- **App-wide banner.** A thin "Mirroring <primary>" bar lives in `PhoneNavHost`
  (above the NavHost, so it's on every tab — Brew/Scale/Profiles/Beans/History/
  Settings — not just Brew). It takes the status-bar inset and the content Box
  below `consumeWindowInsets(statusBars)`, so each screen's own TopAppBar doesn't
  re-pad (no double gap). Flips to "Reconnecting to <primary>…" on a link drop
  (reuses `mirrorReconnecting`). Tappable → a hoisted Devices sheet (Stop / Take
  over), so the picker is reachable from any tab. `PhoneNavHost` now takes `vm` +
  `onConnect`; the Brew screen keeps its own bluetooth-icon sheet.
- **primaryName.** `ConfigSnapshot.primaryName = deviceLabel()`; a secondary stores
  it as `MainUiState.mirroringPrimaryName` (cleared on mode switch) → the banner
  reads "Mirroring Kitchen tablet" instead of a bare label.
- **Disabled machine settings on a secondary.** The non-relayed machine writes —
  GHC (Machine), AC mains frequency + mains heater voltage (Advanced), flow
  calibration Apply/Reset (Calibration) — are disabled when secondary (they'd hit
  the read-only core and go nowhere), with a `MirrorControlledNote` card atop the
  Machine + Calibration sections. Local prefs (keep-awake, auto-connect) stay live.

**Validation:** phone secondary showed the banner on Brew, Beans, and Settings;
banner tap from the Beans tab opened the Devices sheet; Settings ▸ Machine showed
the note + a greyed GHC toggle while Keep-awake/Auto-connect stayed enabled. Insets
correct across both the tab top bar and the Machine detail back-bar. (Advanced/
Calibration disables use the identical `&& !secondary` guard — build-verified.)

**Scope note:** phone shell only (the tablet uses `AppNavHost`/`NavigationRail` and
has no secondary picker yet — that's #13; its authority cue rides along there).

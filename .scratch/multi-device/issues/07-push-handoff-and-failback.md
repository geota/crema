# 07 — Push handoff ("hand off TO X") + the old primary re-mirrors the new host

- **Status:** done (push handoff validated; auto-re-mirror wired but NSD/hardware-gated)
- **Severity:** P2
- **Area:** Android (proxy · MainViewModel · picker UI)
- **Depends on:** 01 (two-phase handoff frames + release/reclaim)

## Problem

Two halves of "handoff" are missing after M3:

1. **Only pull exists.** A secondary can grab the DE1 ("Take over"), but a primary can't
   *push* it ("hand this off to the kitchen tablet"). No reverse-direction action.
2. **The old primary goes dark.** After a take-over the former holder drops to NORMAL,
   disconnected — not to a **mirror of the new host**. So you walk away with your phone
   (now primary) and the tablet just sits there blank, instead of becoming your mirror.
   It can't auto-mirror because it doesn't know the new primary's relay endpoint.

## Design

### Push ("hand off TO X")

- A primary's picker lists its connected secondaries (it already knows them — the
  `RelayHub` clients). "Hand off to <device>" sends a `HandoffOffer` (S→C) to that peer.
- The peer prompts ("Take the machine?") → on accept it runs the **same release+acquire
  as #01**, just initiated by the holder: holder releases + grants → peer acquires →
  holder reclaim-timer as the safety net. Reuses #01's machinery entirely.

### Old primary re-mirrors the new host (both directions)

The former holder needs the new primary's relay endpoint to mirror it. Resolve via the
**same discovery the picker uses**:

- On `switchToPrimary`, the taker advertises over NSD (`PeerDiscovery.advertise`,
  role=primary, holdsDe1, relayPort) — already happens via `refreshAdvertisement()`.
- The former holder, after stepping down, **auto-mirrors the discovered DE1-holder** —
  i.e. wire the long-noted "auto-mirror when a primary appears" UX: if I just gave up the
  DE1 and a primary holding it shows up on NSD, switch to secondary against it.
- Belt-and-suspenders for no-NSD nets (emulator): the handoff grant/offer can carry the
  new primary's `host:port` back so the stepped-down device dials it directly.

This makes a handoff a true **role-swap** (A↔B) instead of A→primary, B→dark.

### Emulator caveat

NSD doesn't cross the emulator NAT, so the auto-re-mirror is validated on hardware; the
endpoint-in-frame fallback lets a 2-emulator demo show the full swap (needs a reverse
`adb forward` for the tablet→phone direction).

## Fix (code, sketch)

- `Frame.kt`: `HandoffOffer(id)` (S→C, push); reuse #01's `HandoffGrant` carrying the
  new primary's endpoint for the no-NSD fallback.
- `RelayHub`: track per-session client identity so a primary can target an offer.
- `MainViewModel`: `offerHandoff(clientId)`; on receiving an offer, prompt → accept →
  acquire (reuse #01). After any step-down, `autoMirrorDiscoveredPrimary()` driven by
  `PeerDiscovery`.
- Picker UI: a primary's "Other devices" lists its mirrors with "Hand off"; the new
  host's former primary shows it auto-mirrored.

## Acceptance / Verify

Hardware (or 2-emulator w/ reverse forward): "Take over" on B → B is primary **and** A
auto-becomes B's mirror (A shows B's live machine), not blank. "Hand off to B" from A
does the same from the other direction. Mid-shot offers/takes still refuse (#01/#09).

## Touched files

- `android/app/src/main/java/coffee/crema/ble/proxy/Frame.kt` — `HandoffOffer`
- `android/app/src/main/java/coffee/crema/ble/proxy/RelayHub.kt` — client identity + offer
- `android/app/src/main/java/coffee/crema/ui/MainViewModel.kt` — `offerHandoff`, `autoMirrorDiscoveredPrimary`
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneBrewSheets.kt` — push UI + auto-mirror row

## Comments
<!-- triage + progress notes append below -->

**2026-06-20 — done + 2-emulator validated (push); re-mirror hardware-gated.**

Design choice: built ON the existing `"handoff"` control-verb path (issue 01's safety
core), NOT a dedicated-frame refactor — **push = the primary nudges a specific
secondary to run the normal "Take over."** So all of #01's idle-gate + release +
reclaim machinery is reused; the only new wire is the nudge. (The dedicated
`Handoff`/`Grant`/`Abort` frame refactor stays deferred — not needed for this.)

- **Push:** new `Frame.HandoffOffer(fromName)` (S→C). `RelayHub` tracks per-session
  client identity (clientId/clientName from Hello) + `onClientsChanged`; exposes
  `handoffTargets()` (control-capable mirrors only — a view-only peer's take-over
  would be refused anyway) + `offerHandoff(clientId)`. VM `offerHandoff` (primary) /
  `onHandoffOffered`→`pendingHandoffOffer`→`acceptHandoffOffer`→`requestHandoff`
  (secondary). UI: `MultiDeviceSection` gained a **primary branch** listing
  `mirrorClients` with "Hand off" (shared → phone sheet + tablet Settings); a
  `HandoffOfferDialog` in MainActivity ("Take the machine?").
- **Re-mirror (no dark device):** `armHandoffReclaim`'s "DE1 taken" branch now calls
  `autoMirrorDiscoveredPrimary()` — find the DE1-holding primary on NSD and
  `switchToSecondary` to it. **NSD doesn't cross the emulator NAT, so the cross-device
  re-mirror is hardware-gated** (like the radio-move in #01/#12); on the emulator
  `peers` is empty → no-op, the former holder drops to normal.

**Validation (tablet primary / phone secondary, paired+control):** the tablet's
picker listed "sdk_gphone16k_arm64 · Mirroring this machine · Hand off"; tapping it
raised the phone's "Take the machine?" prompt. **Mid-shot accept → REFUSED** (replay
was at Espresso; idle-gate #09). After the shot (MACHINE Idle/Ready), re-offer +
**accept → phone became primary** (LanRelayServer :33779, role=primary); the tablet
stepped to normal and its `autoMirrorDiscoveredPrimary` no-op'd (no NSD peers).

**Deferred / follow-ups:** (a) the cross-device auto-re-mirror needs hardware (NSD) or
a reverse-`adb forward` + an endpoint-in-frame to demo on emulators — the former
holder doesn't learn the taker's new relay port over the (torn-down) link, so the
clean path is NSD; (b) the dedicated-frame handoff refactor (#01 deferred) if a
stricter ack/multi-DE1 is later needed.

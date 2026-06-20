# 02 — Device pairing / authorization (TOFU): not just "anyone on the LAN"

- **Status:** ready-for-human
- **Severity:** P1
- **Area:** Android (proxy · RelayHub · new pairing UI) — security/trust
- **Depends on:** none

## Problem

The relay **auto-accepts every client**. `RelayHub.handle(Frame.Hello)` replies
`Welcome` to anyone who connects — no prompt, no identity check (the `Hello.token`
field exists but is unused, reserved for the future cloud path). So on any shared
network — an apartment, an office, a café hotspot — **any device running Crema can
mirror your live shots and relay Start/Stop to your machine.** The locked design
(`../RESEARCH.md`, "TOFU pairing") calls for a real "Allow this device?" gate +
remembered identity; M1 deliberately stubbed it to auto-accept. This is the one gap
that makes the feature unsafe to ship to anyone not on a fully trusted LAN.

## Design (TOFU — trust on first use)

1. **First connect = prompt.** When a `Hello` arrives from an unknown `clientId`, the
   primary holds the handshake and shows a host-side prompt: *"Allow **Adrian's phone**
   to mirror and control this machine?"* (`clientName` + a short fingerprint of
   `clientId`). Accept → `Welcome` + remember; Decline → `Denied("not authorized")` +
   the connection closes (frame already exists).
2. **Remember.** Persist accepted `clientId`s (a `pairedDevices` set in prefs / a small
   store). A remembered client skips the prompt on reconnect.
3. **Capability scope (optional, recommended).** Remember each peer as **mirror-only**
   vs **mirror+control**. A mirror-only peer's `Control`/`Handoff` frames are refused
   (`ControlErr("not authorized")`) even though `Notify` flows. Lets you cast to a
   guest's phone without handing them Start/Stop.
4. **Revoke.** A "Paired devices" list in Settings to forget/var a peer; forgetting an
   active one drops its session.
5. **Token continuity (sets up M5 cloud).** On accept, mint a per-peer token; the peer
   stores it and presents it in `Hello.token` next time, so identity survives a
   `clientId` reset (#14) and is reusable for the cloud relay.

### Threat-model notes

- LAN-scoped today (no auth crossing the WS). The prompt is the gate; the token raises
  the bar to "must have been accepted once." Not a substitute for transport auth — a
  hostile LAN can still sniff the (plaintext ws://) telemetry. WSS + token-auth is the
  M5/cloud story; this issue is the **LAN consent** layer.
- The prompt must be **host-side** (the device holding the DE1), never auto-dismiss,
  and default to Deny.

## Fix (code, sketch)

- `RelayHub`: don't auto-`Welcome`. Route `Hello` through an injected
  `authorize: suspend (clientId, clientName) -> Authz` (`Granted(scope)` / `Denied`);
  default-deny off-primary. Gate `Control`/`Handoff` on the session's scope.
- `MainViewModel`: implement `authorize` — check the persisted paired set; else raise a
  UI prompt (a `pendingPairing` StateFlow the Activity renders as a dialog) and suspend
  on the user's choice. Persist on accept.
- `AppPrefs`/store: `pairedDevices: List<PairedDevice{id, name, scope}>`.
- Settings: a "Paired devices" management row.

## Acceptance / Verify

A new device's first `Hello` raises a host prompt; Decline → `Denied`, no mirror.
Accept → mirrors, and reconnect is silent. A mirror-only peer's Stop is refused. Forget
→ next connect re-prompts. (Hardware/2-emulator: drive the prompt from the host UI.)

## Touched files

- `android/app/src/main/java/coffee/crema/ble/proxy/RelayHub.kt` — `authorize` hook + scope gate
- `android/app/src/main/java/coffee/crema/ble/proxy/Frame.kt` — (reuse `Denied`; maybe a `scope` on `Welcome`)
- `android/app/src/main/java/coffee/crema/ui/MainViewModel.kt` — pairing state + persistence
- `android/app/src/main/java/coffee/crema/settings/AppPrefs.kt` — `pairedDevices`
- Settings screen(s) — pairing prompt + "Paired devices" management

## Comments
<!-- Needs a product call on: prompt copy, default scope (mirror-only vs full), whether
     to require pairing at all on a "home" network. Flagged ready-for-human for that. -->

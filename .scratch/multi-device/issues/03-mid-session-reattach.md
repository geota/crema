# 03 — Mid-session re-attach: a primary restart leaves a frozen "Connected" mirror

- **Status:** done
- **Severity:** P1
- **Area:** Android (proxy · ProxyTransport · ReconnectingClientLink)
- **Depends on:** none

## Problem

(Originally "finding #2".) If the **primary restarts** (app relaunch, crash, reboot,
or a `switchToPrimary` re-bind) while a secondary is mirroring, the secondary's
`ReconnectingClientLink` re-dials the socket and the roster comes back showing the DE1
**"Connected"** — but the per-characteristic `observe` subscriptions are **not**
re-established, so **live telemetry never resumes**. The user sees a frozen mirror that
claims it's connected. Confirmed at runtime during the M2a session (re-triggering the
tablet relay → phone roster "Connected", Notifies dead).

Root cause: `ProxyTransport` reconnect is **link-only** (M1 was scoped to startup
retry). On a fresh link the managers still hold their *old* attach + observe flows; the
M1 protocol's "self-heal" (re-`Hello` → re-`Attach` → re-snapshot) was specced
(`../M1-PROTOCOL.md` §6) but never wired into a connect loop.

## Design

### What must re-run on every link re-up

1. Re-send `Hello`, await `Welcome` (re-seed roster).
2. For every device the managers had attached, re-send `Attach` → re-snapshot.
3. Re-open the per-`(address, char)` observe channels so `observe()` flows resume.

The managers above are the natural drivers of (2)/(3) **if** they're told the link
dropped: their session loop already re-`connect`s on a `FAILED` transition. So the
cleanest fix routes a link drop into each device's `connectionState` as a transition
the managers react to — *without* a spurious teardown during a brief blip.

### Two failure grades (keep them distinct — `../M1-PROTOCOL.md` §6)

- **Brief blip** (redial succeeds fast): report `CONNECTING`, **not** `FAILED`, so the
  manager doesn't tear down / reset the core. On reconnect, re-attach transparently.
- **Terminal give-up**: `FAILED` → managers back off + re-attach when the link returns.

### Mechanism

`ReconnectingClientLink` already redials; expose a **reconnect signal** (a
`StateFlow<LinkState>` or a callback). `ProxyTransport` collects it and, on
`Reconnected`:
- re-sends `Hello`; on `Welcome`, re-applies the roster;
- for each address with a live `connectionState` consumer, re-sends `Attach` and lets
  the relay re-snapshot (the snapshot burst re-seeds the core);
- the existing per-char channels stay (they're unbounded) so `observe` resumes once
  Notifies flow again.

Drive `connectionState` for attached devices to `CONNECTING` on link-drop and back to
`CONNECTED` on re-attach, so the **UI distinguishes "socket up" from "telemetry
flowing"** (today it can't — that's why the freeze is invisible). Surface a "reconnecting"
state in the mirror's status badge.

### Edge: primary identity changed

If the thing that restarted is now a *different* primary (e.g., after a handoff), the
re-`Hello` lands on a new relay; the roster/snapshot from it is authoritative. The
re-attach path handles this for free as long as the link endpoint is current
(`ReconnectingClientLink` dials a fixed `host:port`; a moved primary is #07's problem).

## Fix (code, sketch)

- `ReconnectingClientLink`: emit a `StateFlow<LinkState>{ Connecting, Connected, Failed }`
  (it already loops; surface the transitions).
- `ProxyTransport`: collect that flow; on `Connected`-after-drop run the re-handshake;
  drive attached devices' `connStates` to `CONNECTING`/`CONNECTED` accordingly. Refactor
  the one-shot `init { Hello }` into a `connectAndHello()` the loop can re-invoke.
- UI: a "Reconnecting…" mirror status (PhoneBrewScreen badge) distinct from "Mirroring".

## Acceptance / Verify

2-emulator: mirror a live shot, restart the primary relay → the secondary shows
"Reconnecting…" then **resumes live telemetry** (not a frozen "Connected"). A brief
blip never triggers a core reset (no "Disconnected" flash). `ProxyTransport` reconnect
test (loopback: drop + re-pair the link, assert observe resumes + re-snapshot arrives).

## Touched files

- `android/app/src/main/java/coffee/crema/ble/proxy/ReconnectingClientLink.kt`
- `android/app/src/main/java/coffee/crema/ble/proxy/ProxyTransport.kt`
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneBrewScreen.kt` — "Reconnecting…" badge
- `android/app/src/test/java/coffee/crema/ble/proxy/` — reconnect/re-attach test

## Comments
<!-- triage + progress notes append below -->

**2026-06-20 — done.** `ReconnectingClientLink` rewritten to truly reconnect (the
old loop exited on first connect — it never redialed). Now: a persistent dial loop
that pumps each session then redials on drop; `state: StateFlow<LinkState>` +
`reconnects: Flow<Unit>` (fires on each non-first connect); backoff cap dropped
30 s → 5 s. `ProxyTransport` gained a `reconnects` param + `onReconnect()` (re-`Hello`
+ re-`Attach` every tracked-attached address → relay re-snapshots → `observe`
resumes) + `attached` set tracking. VM reflects `link.state` into
`MainUiState.mirrorReconnecting`; PhoneBrewScreen badge shows "· Reconnecting…".
**Validated on 2 emulators:** phone mirroring → tablet relay force-stopped + relaunched
(new port) → phone (PID unchanged, no relaunch) logged `Primary link dropped → will
redial` → backoff retries → on re-forward, `Connected to primary` (2nd) +
`Link reconnected — re-handshaking (re-attach 1)` → **live telemetry resumed** (was a
frozen "Connected"). No crash. Existing proxy tests green (default `emptyFlow` reconnects).

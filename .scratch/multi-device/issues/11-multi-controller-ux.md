# 11 — Multi-secondary / "who's driving" UX

- **Status:** done (loose model)
- **Severity:** P3
- **Area:** Design + Android (proxy · UI)
- **Depends on:** none

## Problem

The fan-out supports **N secondaries** (`RelayHub` serves a list of clients), but only
one was ever tested. It's **safe** — every secondary's `Control`/`Handoff` relays to the
primary's single VM command router, which serializes — but it's **unmodeled**: two
people on two phones can both mash Stop/Start with no indication of who did what or who's
"in control". Edge cases worth a design pass before this is a real multi-user feature:

- Two secondaries relay conflicting intent in the same tick (Start vs Stop) — last-write
  wins at the primary; is that acceptable, or should control be a lease?
- No feedback that "someone else just stopped the shot" — the other mirrors see the state
  change as telemetry but not the *cause*.
- Handoff with multiple mirrors: who can take over, and what happens to the others
  (they should re-mirror the new host — #07).

## Fix (direction — needs product input)

Options, pick per the intended use (probably "casual home" → keep it loose):

- **Loose (recommended for v1):** keep last-write-wins; add a transient "Adrian's phone
  started a shot" toast fanned to other mirrors (the `Control` already names the
  originator at the relay — fan a lightweight `event` frame).
- **Lease:** an explicit "control token" a secondary requests; others are view-only until
  released. Heavier; only if multi-user-control becomes a real requirement.

Mostly a **design decision** — filed needs-triage to make the call before building.

## Acceptance / Verify

n/a until scoped. Minimum bar once decided: two mirrors + a primary, conflicting control,
no crash/inconsistent state (already holds); chosen feedback model implemented.

## Touched files

- TBD per the chosen model (`RelayHub` originator fan-out; UI toasts; optional lease state)

## Comments
<!-- Needs a product call: is concurrent multi-user *control* a goal, or is mirror-many +
     control-one enough? That decides loose-vs-lease. -->

**2026-06-25 — DONE, "loose" model** (product call: casual-home → keep last-write-wins,
add the originator toast). Implemented:
- New display-only `Frame.Event(text)` (`PROXY_PROTOCOL_VERSION` → 2). `RelayHub.broadcastEvent(text, exceptClientId)` fans it to every mirror EXCEPT the originator.
- The relay threads the originating mirror's id+name into `controlHandler`; `MainViewModel.handleRelayedControl` builds a "<who> <did what>" phrase from the **pre-action** state (so IDLE reads as stop-vs-wake) and, on a successful dispatch, fans the notice to the other mirrors + surfaces it on the primary via `notifyUser`. The originating secondary shows nothing (it already sees the telemetry).
- `ProxyTransport.onEvent` → a secondary surfaces it as a snackbar (the existing #08 path).
- Scope: machine-control verbs only (start shot/steam/water/flush, stop, sleep, tare); config edits stay quiet. **Primary**-initiated actions aren't announced to mirrors in v1 (they see the telemetry) — easy to add later. Concurrency-safe: originator is a per-call param, not shared state, so two mirrors acting at once attribute correctly (last-write-wins on the machine, as designed).
- **Unit-validated** (`ProxyControlTest`): the relay threads the originator, and the event reaches another mirror but **not** the originator (2 clients, in-process). Full 2-device on-device demo not run this session — the control-relay (#T1b) and snackbar (#08) paths it composes are independently hardware-validated. The **lease** model stays the documented alternative if exclusive multi-user control is ever needed.

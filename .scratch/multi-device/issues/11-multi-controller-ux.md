# 11 — Multi-secondary / "who's driving" UX

- **Status:** needs-triage
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

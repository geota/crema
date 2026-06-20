# 09 — Handoff idle-gate: require *known*-idle, not "not-known-busy"

- **Status:** done
- **Severity:** P2
- **Area:** Android (MainViewModel)
- **Depends on:** none (refines 01)

## Problem

`grantHandoff` refuses with a **denylist**:

```kotlin
private val handoffBusyStates = setOf("Espresso", "Steam", "HotWater", "Flush")
require(state !in handoffBusyStates) { "machine busy ($state) — handoff is idle-only" }
```

If `machineStateName` is `null` (state not yet decoded, a momentary gap, just-attached),
`null !in handoffBusyStates` is `true` → it **grants**. An undecoded moment is exactly
when you least want to move the radio. A denylist also silently lets any *new/unknown*
state through as "idle".

## Fix

Flip to an **allowlist of known-idle states** — grant only if the machine is positively
idle (e.g. `Idle`, `Sleep`, `GoingToSleep`, `Refill`, `Heating` if you consider warm-up
fair game). Anything else — including `null`/unknown — refuses. Confirm the exact
`machineStateName` strings against the core's state mapping before locking the set.

```kotlin
private val handoffIdleStates = setOf("Idle", "Sleep", "GoingToSleep", "Refill" /*, "Heating"? */)
require(state in handoffIdleStates) { "machine not idle ($state) — handoff is idle-only" }
```

## Acceptance / Verify

Unit/runtime: handoff during espresso/steam/hot-water/flush refuses (unchanged); handoff
with `machineStateName == null` now **refuses** (was granting); handoff when genuinely
idle grants. Surface the refusal (#08).

## Touched files

- `android/app/src/main/java/coffee/crema/ui/MainViewModel.kt` — `grantHandoff` gate
- verify state strings: `core/de1-app` state → name mapping (and the Kotlin `machineStateName` source)

## Comments
<!-- triage + progress notes append below -->

**2026-06-20 — done.** Flipped `grantHandoff` from a busy-denylist to an idle
**allowlist** `{Sleep, GoingToSleep, Idle, SchedIdle}` (verified against the core
`MachineState` enum in `de1-protocol/src/state.rs`) — `null`/unknown now refuses
(was granting). Mid-shot refuse + idle grant unchanged (both validated in the M3
run); the new null→refuse path is the fix. Compile-verified.

# 01 — Two-phase handoff: a failed take-over must never orphan the machine

- **Status:** ready-for-agent
- **Severity:** P1
- **Area:** Android (proxy · MainViewModel)
- **Depends on:** none (07 depends on this)

## Problem

M3 handoff (commit `0f32e24`) reuses the control relay as a `"handoff"` verb with
**delay-based coordination**: the holder's `grantHandoff` idle-checks then schedules
`switchToNormal()` at +400 ms; the taker's `requestHandoff` does `switchToPrimary()`
at +600 ms. Failure modes on real hardware (BLE is single-central, so the taker can
only acquire the DE1 *after* the holder releases it):

- **Orphaning.** The holder releases the radio unconditionally. If the taker then
  fails to BLE-connect the just-released DE1 (race with the DE1's re-advertise, the
  taker's BT off, a crash), **no device holds the machine** and nothing reclaims it.
  On the emulator this was masked — the taker fell back to replaying the shot it had
  recorded while mirroring, so it never needed a real radio.
- **No rollback.** There is no path back to the holder if the taker fails.
- **Timing-by-hope.** The 400/600 ms windows are guesses, not guarantees.

## Design

BLE single-central makes a brief unowned window **unavoidable** (holder must release
before taker can acquire). The fix is to make that window **short and always
recoverable** via *release + reclaim-on-timeout*, not a true 2PC.

### New frames (replace the `"handoff"` control-verb hack)

| Frame | Dir | Fields | Meaning |
|---|---|---|---|
| `Handoff` | C→S | `{id}` | taker asks to take the DE1 |
| `HandoffGrant` | S→C | `{id, de1Address, de1Name}` | holder is idle + **has released**; here's the DE1 to grab |
| `HandoffAbort` | S→C | `{id, reason}` | holder refuses (busy) — taker stays put |

(Keep them in `Frame.kt` alongside `Control*`; bump nothing — additive.)

### Sequence (pull / "Take over")

1. Taker (secondary) → `Handoff`.
2. Holder (primary) `grantHandoff`:
   - **Busy** (machineState ∈ shot states, see #09) → `HandoffAbort("busy")`. Done.
   - **Idle** → record `de1Address`/`de1Name`, **release the DE1** (`ble.disconnect()`),
     reply `HandoffGrant(de1Address, de1Name)`, then `switchToNormal()`, and arm a
     **reclaim timer** (`RECLAIM_MS`, ~8 s).
3. Taker on `HandoffGrant`: `switchToPrimary()` → its `connect()` scans for a DE1 and
   binds the now-free one (prefer matching `de1Address`). On success it is the new
   primary and starts its relay. Config already mirrored (T2), so it carries over.
4. **Reclaim** (holder, after `RECLAIM_MS`): try to re-acquire the DE1.
   - DE1 **busy** (the taker holds it) → handoff succeeded; holder stays NORMAL
     (and, per #07, re-mirrors the taker).
   - DE1 **free** (the taker failed) → holder re-binds it and reverts to PRIMARY.
     **Machine recovered — no permanent orphan.**

The holder needs no success report from the taker: re-acquire-if-free is the implicit
ack. `RECLAIM_MS` must exceed the taker's worst-case BLE connect (tune on hardware;
start 8 s). The reply (`HandoffGrant`) must flush before the holder's relay tears down
— deliver it *before* calling `switchToNormal()` (it's a `viewModelScope.launch`, so
order the `deliver` then the launch).

### Ordering edge

If the taker connects *after* the holder has already reclaimed (taker was slow > 8 s),
BLE single-central means one wins and the other's connect fails cleanly — no double
ownership. Acceptable; widen `RECLAIM_MS` if it bites.

## Fix (code)

- `Frame.kt`: add `Handoff`/`HandoffGrant`/`HandoffAbort`.
- `ProxyTransport`: a `handoff(): Result<HandoffGrant>` (parallels `control`); dispatch
  the two reply frames.
- `RelayHub`: a `handoffHandler: suspend () -> Result<HandoffGrant?>` ctor param +
  serve-loop case; deliver the grant **before** the handler's side-effects tear down.
- `MainViewModel`: replace the `"handoff"` control verb with the real frames;
  `grantHandoff` → release + grant + arm reclaim; add `reclaimDe1IfFree()`; `requestHandoff`
  → on grant `switchToPrimary()`, on abort surface the reason (#08).

## Acceptance / Verify

2-emulator (or hardware): force the taker to fail acquisition (no capture on the taker,
BT off) → after `RECLAIM_MS` the **original holder is primary again** (logcat: it
re-binds + `LanRelayServer listening`), not orphaned. Mid-shot take-over still aborts
(#09). Happy path: taker becomes primary, holder steps down and does not reclaim.

## Touched files

- `android/app/src/main/java/coffee/crema/ble/proxy/Frame.kt`
- `android/app/src/main/java/coffee/crema/ble/proxy/ProxyTransport.kt`
- `android/app/src/main/java/coffee/crema/ble/proxy/RelayHub.kt`
- `android/app/src/main/java/coffee/crema/ui/MainViewModel.kt` — `grantHandoff`, `requestHandoff`, new `reclaimDe1IfFree`
- `android/app/src/test/java/coffee/crema/ble/proxy/` — new `ProxyHandoffTest` (grant/abort + reclaim-on-free)

## Comments
<!-- triage + progress notes append below -->

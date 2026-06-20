# 12 — Real-DE1 hardware validation pass

- **Status:** ready-for-human
- **Severity:** P1
- **Area:** Validation (hardware-gated)
- **Depends on:** none (but do after 01/03/04 land for a complete pass)

## Problem

Everything M1→M3 is validated against a **replayed** capture, which exercises every
layer — frames, fan-out, read-only gate, control relay, config sync, role-swap — **except
the two things that touch the physical radio**: an actual BLE *write* landing on a DE1,
and the radio physically *moving* between two devices' Bluetooth stacks during a handoff.
Those reuse the same single-device code paths that already work, so risk is moderate —
but "never run against metal" is exactly where a BLE-stack/timing quirk hides. This is
the one gap that *requires* hardware.

## Test plan (needs a DE1 + ≥2 Android devices + a supported scale)

1. **Drive from a mirror.** Phone B mirrors tablet A (real DE1). From B: Start a shot →
   the **real machine starts**; tare; Stop → it stops. Confirm latency is usable and the
   relayed control actually lands as a BLE write (not just logged).
2. **Live mirror fidelity.** B's pressure/flow/temp/chart track the real shot in
   real time; **scale weight mirrors** (needs #04) and matches.
3. **Read-only safety.** Two devices, real machine, a real SAW shot: confirm only the
   primary writes (no double-stop, no fighting) — the read-only gate holds on metal.
4. **Handoff (the big one).** Idle: B "takes over" → B's Bluetooth **actually acquires
   the DE1**, A releases; verify no orphan, the reclaim-on-timeout (#01) recovers a
   forced failure (B's BT off mid-handoff). Mid-shot take-over refuses.
5. **Reconnect.** Kill/relaunch the primary app mid-mirror → B recovers live telemetry
   (#03), not a frozen "Connected".
6. **NSD discovery.** Real LAN: devices find each other without the debug manual peer;
   note any AP/multicast-filtering issues.

## Acceptance / Verify

All six pass on hardware; file any metal-only quirks (BLE release/re-advertise timing,
NSD reliability, write latency) as follow-ups. Record `RECLAIM_MS` (#01) tuned to the
observed DE1 release/re-acquire time.

## Touched files

- none (validation) — but expect tuning PRs to `MainViewModel` (timeouts) + bug fixes.

## Comments
<!-- Hardware-gated; assign when a DE1 + 2 phones + scale are available. -->

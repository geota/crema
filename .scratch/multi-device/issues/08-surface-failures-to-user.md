# 08 — Surface multi-device failures to the user (not just the event log)

- **Status:** ready-for-agent
- **Severity:** P2
- **Area:** Android (MainViewModel · UI)
- **Depends on:** none

## Problem

Every multi-device failure path goes to `appendLog` — the in-app **event log** (a debug
panel), not a user-facing snackbar. "Handoff refused — machine busy", "Relay <verb>
failed", "Config snapshot parse failed", "control rejected" — from the user's seat these
all look like **nothing happened**. For a feature whose entire job is acting at a
distance, silent failures are the wrong default: a refused handoff or a dropped relay
needs visible feedback.

(Note: `appendLog` does not even reach logcat — it only updates `_ui.eventLog`. So these
are invisible to the user *and* to a tail-the-logs debugger.)

## Fix

- Route the user-relevant multi-device failures through `notifyUser(...)` (the existing
  snackbar path) in addition to `appendLog`:
  - `requestHandoff` refusal → "Can't take over — machine busy".
  - `relayIfSecondary` `onFailure` → "Couldn't reach the machine — change not applied"
    (pairs with #06's revert).
  - `applyRemoteConfig` parse failure → quiet log is fine (not user-actionable).
  - handoff grant/abort outcomes (#01) → "You now hold the machine" / "Take-over failed".
- Keep the verbose detail in `appendLog`; the snackbar is the human-readable summary.

## Acceptance / Verify

2-emulator: tap "Take over" mid-shot → a snackbar "machine busy", not a no-op. Kill the
link, change a setting → a snackbar, not silence.

## Touched files

- `android/app/src/main/java/coffee/crema/ui/MainViewModel.kt` — `notifyUser` at the failure points

## Comments
<!-- triage + progress notes append below -->

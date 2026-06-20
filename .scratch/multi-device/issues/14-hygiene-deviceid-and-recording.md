# 14 — Hygiene: persist device identity; stop a mirror recording every shot

- **Status:** ready-for-agent
- **Severity:** P3
- **Area:** Android (MainViewModel · core capture)
- **Depends on:** none

## Problem

Two small but real housekeeping bugs:

1. **Per-launch device identity.** `proxyDeviceId` is a fresh `UUID.randomUUID()` every
   launch (not persisted). So NSD self-filtering churns, "remember this device" (pairing
   #02) can't be stable across restarts, and a peer looks like a new device each launch.
2. **A mirror records every shot it watches.** The capture recorder rides the same
   notification path the core decodes, so a **secondary** writes a `session-*.jsonl` for
   every shot it mirrors (this is *why* the phone-as-new-primary had a capture to replay
   in the M3 demo). Harmless per-file, but it accumulates, and combined with the
   replay-primary's `newestCapture()` it creates a re-recording feedback loop (replay → re-
   record → that becomes "newest" → replay the re-recording…), which is why the demo
   recipe has to `rm` the captures dir each run.

## Fix

1. Persist `proxyDeviceId` in `AppPrefs` (generate once, reuse). Feeds NSD + pairing (#02).
2. Don't record while mirroring: gate the capture recorder off when `proxyRole == "secondary"`
   (or when the transport is a `ProxyTransport`). The mirror has no business writing
   capture files; the primary already records the authoritative session. Optionally also
   make the replay-primary ignore its own re-recordings (`newestCapture` skips files it
   just wrote / a recording made by a replay session).

## Acceptance / Verify

`proxyDeviceId` is identical across app restarts (check prefs). A secondary mirrors a
full shot and writes **no** new `session-*.jsonl` to its captures dir. The replay-primary
demo no longer needs a manual `rm` to avoid replaying a re-recording.

## Touched files

- `android/app/src/main/java/coffee/crema/settings/AppPrefs.kt` — persist `proxyDeviceId`
- `android/app/src/main/java/coffee/crema/ui/MainViewModel.kt` — read/persist id; gate recording off when secondary
- `core/de1-app/src/lib.rs` (capture) and/or the Kotlin recorder wiring — secondary/ replay recording suppression

## Comments
<!-- triage + progress notes append below -->

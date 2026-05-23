# 31 — Follow-ups

**Status:** 2026-05-23. Consolidated open items after the write-side + scale-parity sweeps. Reference for the next planning conversation.

## How to read this doc

- **Sized work** has a rough effort estimate.
- **Open questions** need a decision before sizing.
- **Hardware-verify** items can't be closed without real-machine testing.
- **Deferred** is intentional — listed for visibility only.

---

## 1. Hardware verification (no implementation needed; just smoke-test on real machines)

These exist in code today but need physical-hardware confirmation before we can say they're definitely correct:

- **Atomheart Eclair service UUID** — PR-G adopted reaprime's `b905eaea-6c7e-4f73-b43d-2cdfcab29570` over legacy's. Legacy + reaprime share only the first 32 bits; sniffer trace would confirm. Risk: if reaprime is wrong, Crema's Atomheart users can't pair.
- **Bookoo timer XOR** — Crema's structural `const fn command()` generates checksums that disagree with BOTH upstream apps (which both ship `0x0A/0x0D/0x0C` where the structural answer is `0x0D/0x0C/0x0F`). Per `docs/29` + `docs/30`, either the Bookoo silently tolerates wrong XORs (and legacy's timer has worked anyway) or it silently rejects them (and legacy's timer has never worked). Sniffer trace required.
- **Decent Scale heartbeat cadence** — picked 2s (between legacy's 1s and reaprime's 4s). Verify the scale stays awake; back off to 1s if drops observed.
- **Decent Scale power_off on v1.0/v1.1** — `DecentScaleFirmwareVersion` enum returns `false` for `supports_power_off()` when version is `Unknown` or v1.0/v1.1. Test that a v1.2+ scale actually accepts the byte.
- **Decent Scale buffer-drop with single-write** — we ship one write per command (no double-send). If real hardware drops commands, follow up with reaprime-style data-flow watchdog (NOT blind double-send).
- **Heater voltage `+1000` encoding** — verified via fixture test against reaprime's docstring; not yet against a real DE1's read-back.
- **GHC mode write `0x803820`** — wired but the toggle interactive behavior on connected vs disconnected has only been smoke-tested in dev.

## 2. Open questions (need a decision)

- **BlackCoffee scale** — reaprime-only. Detection: `ffb0` service only, no advertised-name prefix. Is anyone actually using this scale, or is it experimental? If real customers, add a minimal codec. If experimental, skip indefinitely.
- **Eureka Precisa auto-off-on-sleep Settings toggle** — core method exists from PR-G; Settings UI deferred. Default OFF was the agreed shape. Decision needed: where does it live — Machine section gated on Eureka detection, or a new Scale section?
- **Heater-tweaks granular UI** — Crema's per-register setters #47-#51 are TODO (task #54). The composite `set_heater_tweaks` works as a batched 8-write. Granular dials are a power-user feature. Worth building?
- **Firmware update v2** (task #55, `docs/17`) — large multi-day project. Until shipped, users keep legacy de1app side-by-side for FW updates. Prioritize, or accept the side-by-side reality indefinitely?
- **Chart axis units** (task #56) — LiveChart + StaticShotChart y-axes don't respect weight/temp/pressure unit prefs. Channel-readout cards already do. Charts are shape-reading not value-reading; low priority but the partial coverage remains.
- **Bean library implementation kickoff** — `docs/28-bean-roaster-library.md` (5,583 words) is design-only. Build sequence (Phase 0 = local CRUD + denormalized ShotBean → Phase 1 = brew-page integration → Phase 2 = Beanconqueror import → Phase 3 = Visualizer sync) is sketched. Approve to start Phase 0?
- **Atomheart UUID + Bookoo XOR** — see §1 hardware verification.

## 3. Settings composition gaps (small fixes; not blocking)

- **GHC toggle when disconnected** — PR-#47 added `disabled={!connected}`. Watch for regressions.
- **autoPurgeAfterSteam × groupFlushBeforeShot race** — PR-#47 added the `rinsePending` in-flight guard. Soak-test: steam → instant-Coffee within 1.5s. Confirm the purge bails.
- **Water chemistry rows** — pilled `notImplemented` in PR-#47. Eventually wire to `v2-export.ts:47` (which hardcodes `null`) or drop the rows entirely.
- **visualizer\* fields** — preserved in `DEFAULT_SETTINGS` with comment. Promote to real wiring when visualizer.coffee OAuth + upload queue lands.
- **Maintenance steppers + banner** — PR-#47 + PR-A landed both. Soak-test the due-banner trigger across all three (filter/descale/backflush) and confirm dismiss-state-in-memory behaves on reload.

## 4. Design TODOs (carried over from earlier session)

- **#42** "DE1 not connected" empty state on brew page — likely skip; the foot-strip's PowerButton already communicates state.
- **#43** Collapse `QStepper` / `StStepper` / `PeNumber` into one component — refactor for consistency. Sized: ~half-day.
- **#44** Global `:focus-visible` ring + per-control sweep — accessibility polish.

## 5. Scale follow-ups (post PR-G)

- **Bookoo timer XOR sniffer** (see §1)
- **Atomheart UUID sniffer** (see §1)
- **BlackCoffee codec** (see §2)
- **Eureka auto-off UI** (see §2)
- **Reaprime-style watchdog-retry** for scales that need reliability mitigation — only ship if real hardware shows drops. Pattern: track `_ticksSinceLastData`; fire one-shot retry when stale.
- **Decode-side parity audit** — confirm every scale that has additional notification-side decode in reaprime (Atomheart timer, Bookoo battery, Acaia battery, Felicita battery, Varia AKU battery) is surfacing those values into the `Scale` trait + snapshot. PR-G wired most; verify holistically with a test pass.

## 6. Webhook follow-ups (post #57)

- **HMAC signing** — v1 has no signing, no auth. If users need it, add an optional `webhookSecret` field + sign payloads with `HMAC-SHA256` + `X-Crema-Signature` header.
- **Retry strategy** — currently fire-and-forget. If a user's endpoint is sometimes down, an in-memory retry queue with exponential backoff (up to 3 retries, max 30s total) would be useful. Persist nothing — a transient outage shouldn't block shot recording.
- **Visualizer.coffee bridge via webhook** — could front-run the full sync work by letting users point the webhook at a Visualizer-compatible endpoint and getting basic upload that way. Hacky but might land first.

## 7. Calibration follow-ups (post PR-B)

- **Sensor calibration round-trip test on real DE1** — fixture tests pass; real-DE1 round-trip (Apply → re-read → confirm value) hasn't been done.
- **Calibration UI for the flow multiplier (MMR `0x80383C`)** — separate from the 3-axis characteristic (PR-B). Currently exposed as `set_calibration_flow_multiplier` in core; no UI. Decision: surface as a separate "Flow trim" knob in Advanced, or fold into the Calibration section as a fourth row?

## 8. Heater voltage / Hz hardening follow-ups (post PR-C)

- **Country detection failure UX** — currently silent fall-back to no-mismatch-warning. If `ipinfo.io` blocks the user's IP (some ad blockers), they get no warning at all. Acceptable v1; consider a "Detect failed; verify your outlet manually" banner.
- **Voltage / Hz Settings UI placement** — currently in Advanced → Service-grade. Some users may never find them. Decision: surface as a one-time setup prompt on first DE1 connect?

## 9. Settings reset (post PR-D)

- **Confirm dialog escalation** — currently `window.confirm`. Some platforms render this badly. Consider migrating to the inline scrim modal pattern PR-C established.
- **Reset UX after success** — currently a toast. Consider a redirect or refresh of read-only values so users see the new defaults reflected.

## 10. Documentation backlog

- `docs/14-write-actions-audit.md` is materially stale — most rows shipped via PR-A through PR-E. Either retire the doc or mark sections "superseded by docs/27" with line-pointers.
- `docs/15-app-level-writes.md` is shell-side concerns, untouched by the write-side sweeps. Confirm it's still relevant or retire.
- `docs/17` (firmware update v2 plan) — still v2 and accurate. No change.
- `docs/21` (write-on-configure MMR plan) — phases 1-5 mostly shipped. Phase 6 (Bengle peripherals) is blocked on Decent FW UUID publication.

## Quick stat summary

- **Hardware-verify items**: 7
- **Open questions needing a decision**: 7
- **Small composition gaps**: 5
- **Design TODOs**: 3
- **Scale follow-ups**: 6
- **Webhook follow-ups**: 3
- **Calibration follow-ups**: 2
- **Heater V follow-ups**: 2
- **Settings reset follow-ups**: 2
- **Doc backlog**: 4

**Total open items:** 41. None blocking shipping a v1; all are polish, hardware confirmation, or feature expansion.

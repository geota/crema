# Multi-device — known gaps & rough edges (issue index)

Backlog opened 2026-06-20 from the post-M3 gap review. The feature (M1→M3) is
built + 2-emulator validated; these are the thin spots. Severity P0 (blocker) →
P3 (nice-to-have). See each file for detail; the complex ones carry a `## Design`.

**Progress (2026-06-20):** 9 of 14 done + emulator-validated — #01 #03 #05 #06 #08
#09 #10 #13 #14. **5 remain:** #02 (pairing — product), #04 (scale — hardware
double-count risk), #07 (push-handoff — needs #01 dedicated frames), #11
(multi-controller — design), #12 (real-DE1 hardware). Branch unpushed.

| # | Issue | Sev | Status | One-liner |
|---|---|---|---|---|
| [01](issues/01-two-phase-handoff.md) | Two-phase handoff (no orphaned machine) | P1 | done | release→reclaim-on-timeout so a failed take-over can't leave the DE1 owned by no one |
| [02](issues/02-device-pairing-tofu.md) | Device pairing / authorization (TOFU) | P1 | ready-for-human | today *anyone* on the LAN can mirror + drive your machine; add an "Allow this device?" gate |
| [03](issues/03-mid-session-reattach.md) | Mid-session re-attach (frozen-mirror fix) | P1 | done | a primary restart leaves the mirror "Connected" but frozen; re-run the attach handshake on link re-up |
| [04](issues/04-mirror-the-scale.md) | Mirror the scale, not just the DE1 | P1 | ready-for-agent | a secondary's WEIGHT card is always "—"; the scale isn't in the roster |
| [05](issues/05-sync-custom-profiles.md) | Sync custom profiles to mirrors | P2 | done | config sends the profile *id*; a mirror missing that custom profile shows a different one |
| [06](issues/06-robust-config-write-path.md) | Robust config write path | P2 | done | a failed config relay silently eats the change; relay the remaining verbs; optimistic apply |
| [07](issues/07-push-handoff-and-failback.md) | Push handoff + bidirectional re-mirror | P2 | ready-for-agent | "hand off TO X"; the old primary should re-mirror the new host, not go dark (needs 01) |
| [08](issues/08-surface-failures-to-user.md) | Surface multi-device failures to the user | P2 | done | refusals/relay-fails go to a debug log, not a snackbar — they read as "nothing happened" |
| [09](issues/09-handoff-idle-gate-known-idle.md) | Handoff idle-gate: require *known*-idle | P2 | done | an undecoded (null) machine state currently grants; flip to allowlist |
| [10](issues/10-app-wide-authority-signaling.md) | App-wide "you're a mirror" signaling | P2 | done | the cue is Brew-only; Settings/Profiles on a secondary look local |
| [11](issues/11-multi-controller-ux.md) | Multi-secondary / who's-driving UX | P3 | needs-triage | N mirrors is safe but unmodeled — no notion of who's in control |
| [12](issues/12-real-de1-hardware-validation.md) | Real-DE1 hardware validation pass | P1 | ready-for-human | the one true gap — physical BLE write + radio-move never run against metal |
| [13](issues/13-tablet-side-picker.md) | Tablet-side multi-device picker | P3 | done | only the phone can initiate mirror/handoff |
| [14](issues/14-hygiene-deviceid-and-recording.md) | Hygiene: persist deviceId; stop mirror-recording | P3 | done | per-launch identity; a mirror records every shot it watches |

**Pre-trust gate (do before this ships to an untrusted network):** ~~01~~, 02, ~~03~~, ~~08~~ — only **#02** remains.
**Completeness of the mirror:** ~~05~~, ~~06~~, 04. **Then:** 07, 12, 11.

Context: `../M1-PROTOCOL.md` (wire spec), `../RESEARCH.md` (locked design),
`../HANDOFF-M2-2026-06-19.md` (state), memory `multi-device-m1-progress`.

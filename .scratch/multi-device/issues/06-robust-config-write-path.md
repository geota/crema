# 06 — Robust config write path (a failed relay silently eats the change)

- **Status:** done (optimistic relay + revert, all config verbs relayed, brew override round-trips; + a latent control-hang fix)
- **Severity:** P2
- **Area:** Android (MainViewModel · proxy)
- **Depends on:** none (08 for the error surfacing)

## Problem

Three sharp edges in the T2/T2-polish config-write path:

1. **A failed relay silently eats your change.** `relayIfSecondary` relays then the
   setter **early-returns without applying locally**. On success the primary applies +
   pushes back (round-trip update). But if the relay *fails* (link blip), nothing
   happens locally, nothing remotely — only an unseen `appendLog`. Tapping a profile and
   having it just not change, with no error, is a bad surprise.
2. **Round-trip latency on every tap.** Even on success the change waits for
   relay → apply → push-back; on the LAN that's ms, but the UI visibly lags the tap and
   on a congested net it's worse.
3. **Two verbs still aren't relayed.** The QC setters (`setQcSteamTime`/`…FlowMlS`/
   `…TempC`/`setQcHotWaterTemp`/`…VolumeMl`/`setQcFlushTime`/`…TempC`/`setQcGrind`) and
   `quickAdjustBrew` are local-only on a secondary. `quickAdjustBrew` is the awkward one:
   its effect (the live `brewParams` dose/yield/temp override) isn't even in
   `ConfigSnapshot`, so relaying it wouldn't round-trip back.

## Design

- **Optimistic local apply + reconcile.** Change `relayIfSecondary` callers to apply
  locally *immediately* (snappy), then relay; when the authoritative `Config` push
  arrives it reconciles (usually a no-op). On relay **failure**, revert the optimistic
  change and surface it (#08). This trades a rare reconcile flicker for instant feedback
  and no silent loss. (Keep machine-control verbs — start/stop/tare — authority-first, no
  optimism; only *config* verbs go optimistic.)
- **Relay the remaining config verbs.** Wrap the QC setters + `quickAdjustBrew` with
  `relayIfSecondary` and add `handleRelayedControl` cases (same pattern as T2-polish).
  Args: QC = the float as string; `quickAdjustBrew` = the 4 doubles (a small
  `@Serializable BrewAdjust` or CSV).
- **Extend `ConfigSnapshot` for the brew override** so `quickAdjustBrew` round-trips:
  add `brewDoseG`/`brewYieldG`/`brewTempC`/`brewPreinfuseS` (nullable = no override).
  `applyRemoteConfig` sets `_ui.brewParams` from them; `configSnapshotJson` fills them
  from the live `brewParams`.

## Fix (code, sketch)

- `MainViewModel`: a variant `relayIfSecondaryOptimistic(method, args, applyLocal)` for
  config verbs that runs `applyLocal()` then relays + reverts-on-failure; route
  `setActiveProfile`/`setActiveBean`/`setStopOnWeight`/`setAutoTare` through it, plus the
  newly-wrapped QC setters + `quickAdjustBrew`.
- `handleRelayedControl`: add the QC + `quickAdjustBrew` cases.
- `settings/AppPrefs.kt` `ConfigSnapshot`: `+ brewDose/Yield/Temp/Preinf`.
- `configSnapshotJson` / `applyRemoteConfig`: carry the brew override.

## Acceptance / Verify

2-emulator: on a secondary, change a QC value + a brew-adjust → both relay
(`relayed control: setQc…`/`quickAdjustBrew` on the primary) and round-trip back; the
secondary's value updates instantly (optimistic) and matches the primary. Kill the link,
change a setting → the change reverts + an error shows (#08), not a silent no-op.

## Touched files

- `android/app/src/main/java/coffee/crema/ui/MainViewModel.kt` — optimistic relay + QC/brew verbs + handler cases
- `android/app/src/main/java/coffee/crema/settings/AppPrefs.kt` — `ConfigSnapshot` brew override

## Comments
<!-- triage + progress notes append below -->

**2026-06-20 — done + 2-emulator validated.** All three edges addressed:
- **Optimistic local apply + revert.** New `relayConfigOptimistic(method, args, revert)`:
  the caller updates `_ui` at once (snappy), relays, and on **failure** runs `revert`
  + a "change reverted" snackbar — no silent loss, no round-trip lag. Distinct from
  `relayIfSecondary` (kept authority-first for start/stop/tare). Routed
  setActiveProfile/Bean/StopOnWeight/AutoTare + all QC setters + quickAdjustBrew/
  resetBrewParams through it (each captures its prev value for the revert).
- **All config verbs now relayed.** Added handleRelayedControl cases for the 8 QC
  setters (`setQc*`, float-as-string) + `quickAdjustBrew` (CSV "dose,yield,temp,preinf")
  + `resetBrew`. Previously local-only on a secondary (diverged until re-attach).
- **Brew override round-trips.** `ConfigSnapshot` gained `brewDoseG/brewYieldG/
  brewTempC/brewPreinfuseS`; `configSnapshotJson` fills them from the live
  `brewParams`, `applyRemoteConfig` re-arms it (all-null = no override). quickAdjust/
  reset now `pushConfig()` so a mirror's targets track the primary's dial.

**Latent bug found + fixed (`ProxyTransport.request`):** `control`/`read` did
`link.send()` **outside** the 5 s `withTimeout`, but `ReconnectingClientLink.send()`
SUSPENDS until reconnect — so on a dead/mid-reconnect link the request hung forever
(no success, no failure, no revert). Moved the send inside the timeout → fails fast.
This is what makes the revert actually fire (without it the optimistic value just
stuck). The first revert attempt proved it: pre-fix the value stayed; post-fix it
rolled back.

**Validation (tablet primary replay / phone secondary):**
- Steam-time preset on the phone → tablet logged `relayed control: setQcSteamTime
  (20.0)`; phone updated instantly + reconciled. Dose preset → `relayed control:
  quickAdjustBrew (20.0,36.0,79.0,)`; tablet's targets moved to 1.27 oz / 174.2 °F
  (=36 g / 79 °C) — round-trip applied on the primary.
- Fresh attach snapped the phone's steam to the primary's 30 (config sync intact);
  brewParams pushed null → DOSE reset to 18 (primary→mirror override direction).
- **Failure path:** force-stopped the primary, changed steam 30→10 → optimistic 10
  shown, then **reverted to 30** after the 5 s timeout (+ snackbar via the same
  onFailure path). Cue flipped to "Reconnecting…".
- Caveat: rapid taps on the same field while the link is down can interleave reverts;
  the next reconnect Config push reconciles. Best-effort, acceptable.

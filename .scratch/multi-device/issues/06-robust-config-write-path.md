# 06 — Robust config write path (a failed relay silently eats the change)

- **Status:** ready-for-agent
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

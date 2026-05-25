# Maintenance Features Audit (TCL vs reaprime vs Crema)

**Date:** 2026-05-25
**Question:** Do Crema's existing maintenance trackers actually work, and what maintenance features is Crema missing vs the references?

## Summary

Crema's three local maintenance trackers (water filter / descale / backflush)
are **real, fully wired** — the litre counter is driven by a real telemetry
integration, the readouts are derived live from that counter, the brew-page
banner surfaces "due" states, "Mark complete" persists a rebaseline, and the
intervals are user-editable.

The gap is **guided cycles**: reaprime ships dialog-driven `Clean`, `Descale`,
and `SteamRinse` flows that call `requestState(MachineState.cleaning |
descaling | steamRinse)`. Legacy TCL ships `Descale` (with multi-step prep
page) and `Clean` (single-tap). **Crema has the wire codec for all three but
no UI to fire any of them** — only `HotWaterRinse` (group flush) is exposed
as a tap on the brew page.

References disagree on naming: TCL has no "backflush" terminology — the
DE1's `Clean` state (with a blind basket + cafiza) **is** the backflush
cycle. Crema's local "backflush" *hour counter* therefore points at a flow
that exists at the wire (`MachineState::Clean`, `requested_state = 0x12`)
but has no front-end button. Tracker tells you it's due; nothing lets you
do it from Crema.

## Part 1 — Tracker status

| Tracker | Status | Notes |
|---|---|---|
| Water filter | Real | Counter advances from telemetry; threshold + UI banner + reset all wired. |
| Descale | Real | Same litre counter; per-tracker baseline + threshold + reset. |
| Backflush | Real | Hour-based (not litre); resets on `Mark complete`. |

### Water filter

- **Counter advance**: `getMaintenanceStore().accumulate(group_flow, ΔS)`
  is called inside the `Telemetry` event handler at
  `web/src/lib/state/app.svelte.ts:521`. Gated by `countsTowardWater()`
  (`app.svelte.ts:997`) — only `Espresso | HotWater | HotWaterRinse`
  contribute (`WATER_COUNTING_STATES`, `app.svelte.ts:170`). `ΔS` is the
  wall-clock gap between samples, clamped to `MAX_TELEMETRY_GAP_S = 2 s`
  (`app.svelte.ts:131`); a per-sample clamp drops anything `> 1000 ml`
  (`maintenance/store.svelte.ts:31`). Mirrors legacy `de1_de1.tcl:572-595`
  (`volume += GroupFlow × Δt` with the same ±0 / >1000 ml clamps).
- **Threshold**: `readout.filterOk = filterUsedLitres < filterCapacityLitres`
  (`store.svelte.ts:152`), default `filterCapacityLitres = 50`
  (`store.svelte.ts:72`). User-editable via
  `WaterSection.svelte:161 setFilterCapacity`.
- **Persistence**: every write goes through `persist() → writeJson`
  (`store.svelte.ts:111`) under key `crema.maintenance.v1`
  (`store.svelte.ts:24`). State is `$state`-reactive so all readers
  re-render on change.
- **Reset**: `markFilterReplaced()` (`store.svelte.ts:161`) sets
  `filterBaselineLitres = totalLitres` and `filterAtMs = Date.now()`,
  then `persist()`. Wired to the card via
  `WaterSection.svelte:62 onComplete`.
- **UI surface**: card on `WaterSection.svelte:52` ("X% capacity left" /
  "Replace now"), **plus** the brew-page yellow advisory banner
  (`BrewDashboard.svelte:935`-952) — re-derives every render from
  `maintReadout.filterOk`. Dismissal is session-only (re-shows on reload,
  `BrewDashboard.svelte:773-779`).

### Descale

- **Counter advance**: same `totalLitres` integration as the filter — they
  share the litre integral; `descaleSinceLitres = totalLitres -
  descaleBaselineLitres` (`store.svelte.ts:144`).
- **Threshold**: `descaleOk = descaleSinceLitres < descaleIntervalLitres`
  (`store.svelte.ts:154`), default `descaleIntervalLitres = 120 L`
  (`store.svelte.ts:73`). Editable
  (`WaterSection.svelte:176 setDescaleInterval`).
- **Persistence**: persisted with the rest of the maintenance state
  (`store.svelte.ts:111`).
- **Reset**: `markDescaled()` (`store.svelte.ts:171`) sets
  `descaleBaselineLitres = totalLitres`. Wired via
  `WaterSection.svelte:72`.
- **UI surface**: `WaterSection.svelte:64` ("On schedule" / "Descale due")
  + the brew-page banner branch (`BrewDashboard.svelte:767`).

### Backflush

- **Counter advance**: time-based, not flow-based —
  `backflushSinceHours = floor((now − backflushAtMs) / 3 600 000)`
  (`store.svelte.ts:145-148`). Computed on read; no event has to fire to
  "advance" it.
- **Threshold**: `backflushOk = backflushSinceHours <
  backflushIntervalHours` (`store.svelte.ts:156`), default `48 h`
  (`store.svelte.ts:74`). Editable
  (`WaterSection.svelte:191 setBackflushInterval`).
- **Persistence**: same key.
- **Reset**: `markBackflushed()` (`store.svelte.ts:181`) sets
  `backflushAtMs = Date.now()`. Wired via `WaterSection.svelte:82`.
- **UI surface**: `WaterSection.svelte:74` + brew-page banner branch
  (`BrewDashboard.svelte:768-770`).

### Verdict on Part 1

All three trackers are real end-to-end. The implementation is on the
nicer end of what we usually find in this audit series: the integration
mirrors legacy's clamps, the gating by machine state is conservative
(steam / sleep / cal don't count), and the read path is `$state`-
reactive so settings edits live-update the card.

One trivial caveat: the integral runs in **wall-clock** time between
telemetry samples (`performance.now()` deltas, `app.svelte.ts:515-523`),
which is correct in steady state but accumulates ~zero on the very
first sample of a session (no prior `lastTelemetryAtMs`) and is reset
on `disconnect()` (`app.svelte.ts:1075`). That's the same shape as
legacy and produces no over- or undercount at sample rate ≥ 5 Hz.

## Part 2 — Missing maintenance features

| Feature | TCL | reaprime | Crema | Gap | Effort |
|---|---|---|---|---|---|
| Start descale cycle (`MachineState::Descale`) | Yes — prep page + start button | Yes — dialog + button | **Wire only** | YES (UI) | trivial |
| Start clean/backflush cycle (`MachineState::Clean`) | Yes — direct-tap button | Yes — dialog + button | **Wire only** | YES (UI) | trivial |
| Start steam rinse (`MachineState::SteamRinse`) | Yes — proc `start_steam_rinse` | Yes — direct button | **Wire only** | YES (UI) | trivial |
| Group flush / rinse (`MachineState::HotWaterRinse`) | Yes — `start_flush` | Yes (implicit, configurable) | Yes — brew-page tap | OK | — |
| Tank-rinse / drain cycle | No | No | No | No gap | — |
| Cleaning-tablet (cafiza) guided dialog text | No (Clean is one-tap) | Yes (`settings_tile.dart:276`) | No | optional UX | trivial |
| Descale prep instructions in UI | Yes (4-step page) | Yes (Basecamp link + 4 bullets) | No | optional UX | trivial |
| Water-filter firmware-side counter (MMR) | n/a — no such register | n/a | n/a | No (firmware doesn't expose) | — |
| Group warmup / preheat (`EspressoWarmupTimeout`) | Yes — MMR setter only | Yes — MMR setter | Yes — Advanced settings | OK | — |
| Auto post-steam purge (`HotWaterRinse`) | Yes (some skins) | Yes | Yes — `scheduleAutoPurge` `app.svelte.ts:1804` | OK | — |
| Flush flow / temp / timeout MMR setters | Yes | Yes (`setFlushFlow`, `setFlushTemperature`) | Wire ready (`setFlushFlowRate / setFlushTemp / setFlushTimeout`); **only `FlushTemp` has a UI affordance** (the per-brew Flush stepper, `BrewDashboard.svelte:176`) | Partial UI (FlowRate, Timeout missing) | trivial |

### Feature notes

**Start descale cycle.** All three references agree on the wire: a
`RequestedState` write to `cuuid_02` with byte `0x0A`
(`MachineState::Descale`). The user must first fill the tank with
citric-acid solution and install a blind basket — TCL's prep page
(`de1_skin_settings.tcl:1933-1941`) and reaprime's dialog
(`settings_tile.dart:249-273`) both ship the instructions in-app.
Once `Start` is tapped, control delegates to the firmware, which
walks `DescaleInit → DescaleFillGroup → DescaleReturn → DescaleGroup
→ DescaleSteam` substates (Crema's protocol crate knows all five —
`de1-protocol/src/state.rs:107-112`) and returns to Idle. The Crema
shell already auto-binds this to its existing `requestMachineState`
plumbing — `core/de1-wasm/src/lib.rs:230 MachineRequest::Descale →
MachineState::Descale` — but no button calls it. Adding one means a
Settings → Water card "Start descale" action that opens a confirm
dialog (with the prep checklist) and on confirm runs
`app.requestMachineState(MachineState.Descale)`.

**Start clean / backflush cycle.** Same shape: `RequestedState = 0x12`
(`MachineState::Clean`). The user installs a blind basket and (optionally)
adds cafiza-style detergent; the firmware runs `CleanInit → CleanFillGroup
→ CleanSoak → CleanGroup` (substates 13–16, `de1-protocol/src/state.rs:112-115`).
This is what Crema's UI calls "backflush". The legacy TCL skin has a
one-tap button (`de1_skin_settings.tcl:1080`); reaprime has a dialog
(`settings_tile.dart:166-178, 276-283`). Crema's wire is ready
(`crema-core.ts:684 Clean`; bridge map `core/index.ts:910`); only UI is
missing. The existing "Mark complete" button on the backflush card could
be split into "Run now" (fires `requestMachineState(MachineState.Clean)`,
which the firmware drives to completion, then the user taps "Mark complete"
once they've reinstalled the regular basket) and the existing reset.

**Start steam rinse.** `RequestedState = 0x10` (`MachineState::SteamRinse`).
TCL: `machine.tcl:774 start_steam_rinse`. reaprime:
`settings_tile.dart:160-165` "Steam rinse" button. Crema wire:
`MachineRequest::SteamRinse` (`de1-wasm/src/lib.rs`); facade entry
`index.ts:917`. No UI. Adding it is one button next to the existing Flush
button on the brew page (or a Settings → Maintenance row).

**Tank-rinse / drain cycle.** No reference implements one. Drop from the
list.

**Cleaning-tablet guided dialog text.** reaprime adds two extra lines to
the Clean dialog ("Prepare blind basket", "Add cafiza if needed",
`settings_tile.dart:276-283`). Cosmetic; ship the instructions inline
when we add the "Run now" button above.

**Water-filter firmware-side counter.** Searched the DE1 MMR map
(`core/de1-protocol/src/mmr.rs`) and the firmware doesn't expose a
filter-life register. Both reference apps integrate flow on the host
(legacy `de1_de1.tcl:572-595`); Crema does the same
(`maintenance/store.svelte.ts:122`). No gap.

**Group warmup.** All three apps surface the `EspressoWarmupTimeout` MMR
(`0x803838`). Crema exposes it in Advanced Settings
(`AdvancedSection.svelte:158`).

**Auto post-steam purge.** Crema schedules a `HotWaterRinse` ~1.5 s after
a steam session ends (`app.svelte.ts:1804`). Parity with legacy
"auto-flush after steam".

**Flush MMR setters.** Wire-ready for all three (`FlushFlowRate`,
`FlushTemp`, `FlushTimeout` — see `core/de1-protocol/src/mmr.rs:125-153`
and the bridge methods at `core/index.ts:881-889`). UI: only `FlushTemp`
has a knob (`BrewDashboard.svelte:176`, fed through `flushTemp` in
`brew-params.svelte.ts:49`). `FlushFlowRate` and `FlushTimeout` have no
UI — reaprime has all three (`setFlushFlow`, `setFlushTemperature`,
plus a flush timeout via the RinseForm `duration`). Two missing steppers,
or one "Flush settings" group, in Settings → Water.

## Part 3 — Wire-level analysis

| Feature | Codec exists | `CremaCore` method | What's missing |
|---|---|---|---|
| Start `Descale` | `core/de1-protocol/src/command.rs:13 requested_state(MachineState::Descale)` → byte `0x0A` | `core/de1-app/src/lib.rs:630 request_machine_state` (generic); bridge `core/de1-wasm/src/lib.rs:608 request_machine_state(MachineRequest::Descale)`; facade `web/src/lib/core/index.ts:909` | **UI only** — a Settings → Water card or button that calls `app.requestMachineState(MachineState.Descale)`. Optionally a confirm dialog with the citric-acid + blind-basket prep checklist. |
| Start `Clean` (backflush) | Same proc; byte `0x12` | Same path; facade entry `core/index.ts:910` | **UI only** — same shape as descale. |
| Start `SteamRinse` | Same proc; byte `0x10` | Same path; facade entry `core/index.ts:917` | **UI only** — one button. |
| `FlushFlowRate` set | `mmr.rs:125-126 + 185 (0x803840, 4-byte)` | `set_flush_flow_rate` (de1-wasm); facade `core/index.ts:881` | **UI only** — one stepper. |
| `FlushTimeout` set | `mmr.rs:152-153 + 196 (0x803848, scaled int(10×s))` | `set_flush_timeout`; facade `core/index.ts:887` | **UI only** — one stepper. |

Effort: every entry above is **trivial** at the wire — protocol, core, bridge, and facade are all in place. The only file-level changes needed are presentational (one `<button>` per feature, plus optional dialog text).

## Verdict + recommendations

### What works
The local trackers are genuinely wired. We do not need to rebuild the
litre integration; we do not need to rewire the brew-page banner;
"Mark complete" is real.

### Highest-value additions (Tier 1 — should ship)

1. **"Run now" button on the Descale card** in Settings → Water.
   Fires `app.requestMachineState(MachineState.Descale)` after a
   one-screen confirm with prep instructions. Justification: the tracker
   already tells the user descaling is due — leaving them no way to act
   on that warning from inside Crema is a credibility hole. Effort: ~50
   LoC in `WaterSection.svelte` + a confirm dialog.
2. **"Run now" button on the Backflush card.** Same shape, fires
   `MachineState.Clean`. The backflush card without a "run" action is
   currently inviting the user to leave the app to use TCL.
3. **Steam-rinse button.** Either next to the Flush button on the brew
   page or as a third maintenance card. Adopts the existing one-line
   approach. Effort: ~10 LoC.

### Tier 2 (nice-to-have)

4. **FlushFlowRate and FlushTimeout steppers.** Group them with the
   existing `flushTemp` stepper into a "Flush settings" block. The
   wire is already shipped; this is purely an IA decision.
5. **Cleaning-tablet / descale-prep instruction copy** in the confirm
   dialogs for #1 + #2. Ship from day one to match reaprime's parity.

### Out of scope

- **Firmware-side water-filter age register**: doesn't exist.
- **Tank-rinse / drain cycle**: no reference implements it.
- **Cleaning-tablet detection / cafiza dosage helper**: not in any
  reference app.

### Reference disagreement worth flagging

The DE1 firmware's `Clean` state **is** the cycle the espresso world
calls a "backflush" — TCL never uses the word "backflush" at all (no hits
across the entire `de1plus/` tree). Reaprime calls the button "Clean".
Crema's local tracker calls it "Backflush". When we add the "Run now"
button, recommended copy is "**Backflush (cleaning cycle)**" so the
maintenance card and the action share vocabulary, while the dialog text
explains "This runs the DE1's cleaning cycle — install a blind basket and
(optional) cafiza tablet before starting." Per project guidance ("defer
to reaprime when legacy + reaprime disagree"), the action-button name
itself ("Clean") is fine; the card stays "Backflush" because that's the
user-facing word for the operation.

## Sources

- TCL `de1plus/machine.tcl:692-704 start_decaling` — descale flow.
- TCL `de1plus/machine.tcl:723-735 start_cleaning` — clean / detergent cycle.
- TCL `de1plus/machine.tcl:748-772 start_flush` — group flush / `HotWaterRinse`.
- TCL `de1plus/machine.tcl:774 start_steam_rinse` — steam rinse.
- TCL `de1plus/de1_de1.tcl:572-595` — legacy litre integration with clamps.
- TCL `de1plus/skins/default/de1_skin_settings.tcl:1080-1941` — Descale + Clean buttons + prep page.
- reaprime `lib/src/home_feature/tiles/settings_tile.dart:160-202` — Steam rinse / Clean / Descale buttons.
- reaprime `lib/src/home_feature/tiles/settings_tile.dart:204-283` — `_showDialog` + descale/clean dialog body.
- reaprime `lib/src/models/device/impl/de1/unified_de1/unified_de1.dart:229 requestState` — wire write.
- reaprime `lib/src/home_feature/forms/rinse_form.dart` — flush/rinse settings (temp/duration/flow).
- Crema `web/src/lib/maintenance/store.svelte.ts` — full maintenance store.
- Crema `web/src/lib/state/app.svelte.ts:131` `MAX_TELEMETRY_GAP_S` clamp.
- Crema `web/src/lib/state/app.svelte.ts:170-174` `WATER_COUNTING_STATES`.
- Crema `web/src/lib/state/app.svelte.ts:511-524` `Telemetry` → `accumulate`.
- Crema `web/src/lib/state/app.svelte.ts:997-1000` `countsTowardWater()`.
- Crema `web/src/lib/state/app.svelte.ts:1804-1821` post-steam auto `HotWaterRinse`.
- Crema `web/src/lib/state/app.svelte.ts:1952-1968` `requestMachineState`.
- Crema `web/src/lib/components/brew/BrewDashboard.svelte:99-110` Steam / HotWater / Flush taps.
- Crema `web/src/lib/components/brew/BrewDashboard.svelte:742-779, 935-952` maintenance banner.
- Crema `web/src/lib/components/settings/sections/WaterSection.svelte` — full cards + intervals.
- Crema `web/src/lib/components/settings/StMaintenanceCard.svelte` — presentational card.
- Crema `web/src/lib/core/index.ts:893-928 requestMachineState` — wasm enum mapping (Descale / Clean / SteamRinse / HotWaterRinse).
- Crema `web/src/lib/core/index.ts:881-889` flush MMR facade methods.
- Crema `core/de1-protocol/src/command.rs:13 requested_state` — single-byte state write codec.
- Crema `core/de1-protocol/src/state.rs:23, 32-34, 107-115` `MachineState::Descale`, `Clean`, `HotWaterRinse`, `SteamRinse` + descale/clean substates.
- Crema `core/de1-protocol/src/mmr.rs:125-196` Flush MMR registers (`FlushFlowRate`, `FlushTemp`, `FlushTimeout`).
- Crema `core/de1-app/src/lib.rs:630-640 request_machine_state` — `Command::WriteCharacteristic{De1RequestedState}`.
- Crema `core/de1-wasm/src/lib.rs:186-237 MachineRequest` → `MachineState` map.
- Crema `docs/27-write-side-gaps.md:71-73` — Clean / Descale / SteamRinse shipped at wire.

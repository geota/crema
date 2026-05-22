# Code-quality review — `web/`

Scope: `~/code/crema/web/src` — SvelteKit 2 / Svelte 5 PWA, ~23,700 lines TS/Svelte across ~110 files (the brief said "~5000" — the codebase is ~5× that; the rest of this review takes the real size).

Bottom line: the shell is in good shape. The state layer is well-factored, `applyEvent` is a pure function with a clean fold, the orchestrator is the only thing that touches the BLE managers, and screen components read off a single `UiSnapshot`. Most findings below are nits; the few Major items are convention drift that became visible because the convention is otherwise so well-enforced.

Findings are de-duplicated against `docs/13-deferred-refactors.md`: uPlot wrapper consolidation (Task 1), Bookoo abstraction leak (Task 2), button-vocabulary unification (Task 3), and the `StoredShot` v1→v2 break are not re-reported.

---

## Major

### M1 — Unit suffixes on `Event` value-type fields (convention §3.2 violation)

**Location:** `core/de1-app/src/event.rs:119, 332-344` (surfaces in TS at `web/src/lib/state/ui-state.svelte.ts:712-714, 949-960`).

**Issue:** HANDOFF §3.2 explicitly says "Drop unit suffixes on FFI value-type field names too. `Event::Telemetry` has `elapsed: u32` not `elapsed_ms`; units are documented, not named." The rest of `Event::Telemetry` follows the rule (`group_pressure`, `group_flow`, `head_temp`, `mix_temp`, `steam_temp`), but **eight sibling fields don't**:

- `Event::Telemetry.dispensed_volume_ml`
- `Event::ShotSettingsRead.{steam_temp_c, steam_timeout_s, hot_water_temp_c, hot_water_volume_ml, hot_water_timeout_s, espresso_volume_ml, group_temp_c}`

These are pure FFI value types, not wire-decode (§3.3) and not the v2 profile contract (§3.4). The TS bindings inherit the suffixes verbatim and the shell propagates them into `UiSnapshot.dispensedVolumeMl` and the whole `De1ShotSettingsSnapshot.*Ml/*S/*C` family. `Event::ShotCompleted.duration: u32` (also ms) gets it right; `ShotSettingsRead`'s sibling timeouts get it wrong.

**Fix:** rename the fields to drop suffixes, document the unit in the doc-comment (the existing comments already do — e.g. "Steam timeout, seconds."). `dispensed_volume_ml` → `dispensed_volume`. `steam_temp_c` → `steam_temp`. Etc. Regenerate typeshare; the shell renames track through one mechanical pass. The shell-side names should follow: `UiSnapshot.dispensedVolumeMl` → `dispensedVolume`, `De1ShotSettingsSnapshot` field names lose the unit suffixes. This breaks no on-disk format — these fields aren't persisted.

### M2 — `ScaleCapabilities.standby_minutes` (same §3.2 violation, separate type)

**Location:** `core/de1-scale/src/scale.rs:143` → `web/src/lib/core/crema-core.ts:515`.

**Issue:** Same rule, second case. `RangeCapability` for the auto-standby timeout is named `standby_minutes` rather than `standby`. The other range caps in the same type (`volume`) don't carry a unit. The follow-on rename runs through `CremaCore.setScaleStandbyMinutes` → `app.setScaleStandbyMinutes` → `scaleStandbyMinutes` on the snapshot, and the corresponding scale-row UI.

**Fix:** rename to `standby` on the Rust side; let the doc-comment carry "minutes". The TS facade method becomes `setScaleStandby(minutes: number)`; the parameter name keeps the unit (parameters are fine).

### M3 — Plumbed-but-unused read-path snapshot fields

**Location:** `web/src/lib/state/ui-state.svelte.ts:282-323`.

**Issue:** Four `UiSnapshot` fields are folded in by `applyEvent` and **never read by any UI component**: `de1MachineInfo` (partly read by MachineSection — keep), `de1Calibration`, `de1ShotSettings`, `loadedProfileShape`, `machineError`, `idleSince`, `lastShotCompletedAt`, `lastShotDuration`. The field-comments say "deferred UI" and reference doc 11. That's fine as a project decision, but the cost is: every event for these registers grows the snapshot via spread-copy on every fold, every `MachineStateChanged` updates `idleSince` / `machineError`, and any future `MachineSection`-style consumer has to discover them through grep rather than through use.

A quick test: `grep -r "de1ShotSettings\|loadedProfileShape\|machineError\|idleSince" web/src --include="*.svelte"` returns zero hits.

**Fix:** either land the deferred surfaces now (the data has been waiting for ~3 docs to arrive), or move these out of the always-on `UiSnapshot` into a separate `read-path-diagnostics` snapshot the components opt into. The current shape pays the cost of plumbing without the benefit of reading. Cheapest path: do nothing, accept the 7-field overhead, but at least add a `TODO(doc/11)` comment block on each.

---

## Minor

### m1 — Two `ratioLabel` functions on different types

**Location:** `web/src/lib/profiles/model.ts:174` and `web/src/lib/history/model.ts:98`.

**Issue:** Two unrelated exports with the same name, both producing `"1:x.x"`, signatures `(CremaProfile) → string` and `(StoredShot) → string`. The history one even comments "matches `profiles/model.ts` ratioLabel". Two consumers (`ShotDetail`, `ProfileEditor`) import the right one each, but the names being identical means a future rename in either file silently breaks the other.

**Fix:** rename to `profileRatioLabel` / `shotRatioLabel`, or factor a shared `formatRatio(dose, yield): string` helper in `lib/utils/format.ts` and have both call it.

### m2 — Download / timestamp boilerplate duplicated 3 places

**Location:** `web/src/routes/history/+page.svelte:142-158`, `web/src/routes/profiles/+page.svelte:367-378, 392-399`, `web/src/lib/components/history/ShotDetail.svelte:120-128, 136-142`, `web/src/lib/history/model.ts:115-118`.

**Issue:** The `URL.createObjectURL` → anchor-click → `revokeObjectURL` pattern appears six times. The `YYYYMMDDTHHMM` stamp formatter is reimplemented three times. The route-level `history/+page.svelte` is the most explicit (with a `downloadBlob` helper and a `stamp()` function), and is then copy-pasted into the other call sites verbatim.

**Fix:** add `web/src/lib/utils/download.ts` with two exports:

```ts
export function downloadBlob(blob: Blob, filename: string): void { /* … */ }
export function timestamp(date = new Date()): string { /* YYYYMMDDTHHMM */ }
```

Six call sites collapse to one-liners. The two `ShotDetail.download` branches lose 12 lines.

### m3 — `De1State` and `ScaleState` are identical 7-variant unions

**Location:** `web/src/lib/ble/de1.ts:45-52` and `web/src/lib/ble/scale.ts:45-52`.

**Issue:** Identical TS unions. Two state-label maps duplicate them (`MachineSection.svelte:68-80` and `routes/scale/+page.svelte:84-94`) with identical keys and labels. The `connected = ['connecting','subscribing','ready','reconnecting'].includes(...)` logic also appears in both sections.

**Fix:** define `type ConnectionState = 'idle' | 'connecting' | …` in `lib/ble/index.ts`; re-export under both old names if call-site clarity is wanted. Move the state-label map and the `connectedStates` Set there too. Saves ~25 lines, prevents drift.

### m4 — `ShotDetail.download()` is two copies of the same function

**Location:** `web/src/lib/components/history/ShotDetail.svelte:115-143`.

**Issue:** The two branches (`shotExportFormat === 'replay'` and the v2 fallback) are byte-for-byte the same except for the bytes and the filename — 8 lines of object-URL boilerplate copy-pasted with one differing variable each.

**Fix:** with `m2`'s `downloadBlob`, this becomes ~10 lines total:

```ts
async function download(): Promise<void> {
  if (settings.current.shotExportFormat === 'replay') {
    const entries = await getCaptureStore().get(shot.id);
    if (entries?.length) {
      const base = shotFilename(shot).replace(/\.shot\.json$/, '');
      downloadBlob(new Blob([captureJsonl(entries)], { type: 'application/x-ndjson' }), `${base}.jsonl`);
      return;
    }
    console.warn(`No raw capture for ${shot.id}; falling back to v2 JSON.`);
  }
  downloadBlob(new Blob([exportStoredShotAsV2Json(shot)], { type: 'application/json' }), shotFilename(shot));
}
```

### m5 — `CremaApp.setSuppressDe1Sleep` is a no-op kept "for symmetry"

**Location:** `web/src/lib/state/app.svelte.ts:794-799`.

**Issue:** The method body is intentionally empty (comment: "Intentional no-op. The setting is read directly…"). Its only caller is `MachineSection.svelte:373`. Methods that intentionally do nothing are worse than no method at all — the caller is misled into thinking it's writing through.

**Fix:** delete the method. `MachineSection`'s setting toggle already writes through `getSettingsStore()`; the orchestrator never needs to know. Two lines deleted, one no-op call deleted, the heartbeat loop continues to read `settings.current.suppressDe1Sleep` exactly as it does today.

### m6 — Service-mode timer state machine lives in `BrewDashboard` UI

**Location:** `web/src/lib/components/brew/BrewDashboard.svelte:106-205`.

**Issue:** ~100 lines of "when does the steam/water/flush mode tick start, when does it stop, what's its elapsed time" live in the dashboard component, including a `$state` `modeStartedAtMs` / `modeNowMs` pair and a 250 ms `setInterval`. This is exactly the same shape as `idleSince` / `lastShotCompletedAt` / `lastShotDuration` already in `UiSnapshot` (M3). One is in the state layer; one is in the UI. The MODE_TARGET_SEC / MODE_TARGET_LABEL constants similarly belong somewhere the future "per-mode settings" can find them — comments say "they'll come from the per-mode Settings sections once they land".

**Fix:** when the mode-settings panels land, lift `modeStartedAtMs` and the targets into `UiSnapshot` (or a small derived helper in `lib/state`). The 250 ms tick is harder to move cleanly out of a component — leave that — but the time-since logic should stop being component state.

### m7 — `LiveChart` and `StaticShotChart` `untrack` ceremony has subtly diverged

**Location:** `web/src/lib/components/brew/LiveChart.svelte:403-462` and `web/src/lib/components/history/StaticShotChart.svelte:196-246`.

**Issue:** Deferred-refactor Task 1 covers extracting a shared lifecycle. The deferred-refactors doc notes the four charts have "subtly different `untrack` semantics that were just hardened in the review fixes". A spot check shows `LiveChart` and `StaticShotChart` already differ slightly: `LiveChart` reads `theme.current` outside `untrack` and reads `series` etc. untracked; `StaticShotChart` does the same but its data-setData effect reads `series` *tracked* (it builds `data` inline rather than via a `$derived`). Not buggy today, but it's the exact drift the deferred refactor was meant to lock in. Mentioning so the eventual fix knows to align on `LiveChart`'s pattern (which is the more careful one).

**Fix:** when Task 1 lands, standardise on the `LiveChart` pattern: `$derived` for the data, an `$effect` that just calls `chart.setData(derivedData)`, and `untrack` only inside the create / rebuild effects.

### m8 — `CLEARED_DE1_READOUT` is a `Partial<UiSnapshot>` shaped patch — type-check it against drift

**Location:** `web/src/lib/state/app.svelte.ts:51-71`.

**Issue:** The constant lists 11 fields to reset on connect / disconnect / replay-start. It's marked `satisfies Partial<UiSnapshot>`, which catches typos but **does not** catch a new field added to `UiSnapshot` that ought to be cleared and isn't — the comment ("a field added to the read-paths is cleared everywhere the moment it is added here") is aspirational, not enforced. Three fields that are arguably DE1-derived have already been added to the snapshot without being listed: `loadedProfileShape`, `de1ShotSettings`, `activeProfileName`. Whether each of those *should* reset on disconnect is a judgement call (`activeProfileName` plausibly should — the next DE1 might have a different profile loaded), but currently the choice is implicit.

**Fix:** either (a) add explicit `loadedProfileShape: null, de1ShotSettings: null, activeProfileName: null` lines, or (b) write a 5-line unit test (yes, with no test framework — invoke applyEvent / patch in a `.spec.ts` if you ever add Vitest) that confirms every DE1-derived field is in `CLEARED_DE1_READOUT`. Option (a) is one minute.

### m9 — `CremaApp.setLineFrequencyOverride` and `createCremaApp`'s init are subtly out of sync

**Location:** `web/src/lib/state/app.svelte.ts:525-527, 990-995`.

**Issue:** The `createCremaApp` constructor reads the persisted Hz and pushes it to the core. The instance method `setLineFrequencyOverride` does not also persist — it just writes to the core. Callers have to remember to update the setting separately. Look at the lone caller, `AdvancedSection.svelte`:

```svelte
function setLineFreq(hz: ...): void {
    settings.set('lineFrequencyHz', hz);
    void app?.setLineFrequencyOverride(hz);
}
```

Two writes for one logical setting change. If the persist call is forgotten, the next reload silently uses the old value.

**Fix:** make `setLineFrequencyOverride` also write through `getSettingsStore().set('lineFrequencyHz', hz)`. The construction path then becomes a special case (read-from-store-on-boot), but the runtime path has one write. Same shape as `suppressDe1Sleep` — except that one is no-op'd entirely (see m5).

---

## Nits

### n1 — `BrewDashboard` reads `getCremaAppContext()` via `appCtx().app?` lazily everywhere

**Location:** `web/src/lib/components/brew/BrewDashboard.svelte:51-94`.

The `void appCtx().app?.requestMachineState(...)` pattern appears at four tap-handlers and silently no-ops when `app` is null. That's the right behaviour (the wasm core is loading) but each handler also does the lookup independently — `const app = appCtx().app; if (app) …` once at the top would read clearer.

### n2 — `applyEvent`'s switch reaches ~430 lines

**Location:** `web/src/lib/state/ui-state.svelte.ts:623-1054`.

It's a flat switch over ~22 event types. The function is genuinely pure and readable — the size is *necessary complexity*, not accidental. No fix recommended, just a heads-up: if one more half-dozen events land (profile upload added 4, capture replay 0, the not-yet-shipped firmware-update will add at least 4), splitting `applyEvent` into one-fn-per-event-family (`applyShotEvent`, `applyScaleEvent`, `applyProfileEvent`, …) would start to pay off. Not now.

### n3 — `BrewDashboard` is 663 lines, two-thirds template / styles

**Location:** `web/src/lib/components/brew/BrewDashboard.svelte`.

The 394-line script is large but mostly thin: it's a pull of facts off `ui` + `prefs` + `profileStore` + the param model, plus the service-mode state machine (m6). The component does not do any maths the state layer hasn't already done. The template, foot cluster, and styles are screen-shaped and live where they should. Leave it.

### n4 — `BrewDashboard.toggleRun` is a UI stub flipping local `manualRunning`

**Location:** `web/src/lib/components/brew/BrewDashboard.svelte:389-393`.

Marked `// TODO: wire to DE1 control`. Fine as a stub; one issue: the icon/label flips, but nothing else does. If a real shot is in progress (machine pressed the on-machine button), `ui.shotInProgress` is true but the big button still shows "Start extraction". Either drive it off `ui.shotInProgress` or keep the stub and accept the inconsistency.

### n5 — `formatFirmware` and `MACHINE_ERROR_TEXT` live in `ui-state.svelte.ts`

**Location:** `web/src/lib/state/ui-state.svelte.ts:536-541, 551-581`.

Pure functions that don't reference the snapshot. They want to be in a `lib/state/format.ts` (or in `lib/utils/format.ts`) so they can be tested without importing the rest of the snapshot machinery. Same for `waterTankMl`, `waterRefillSoon`, `puckResistance`. Once a test framework lands, the move is mechanical. Until then it's fine.

### n6 — The hardcoded `MACHINE_MODEL_NAMES` table is in `MachineSection`

**Location:** `web/src/lib/components/settings/sections/MachineSection.svelte:142-151`.

Eight-entry lookup of MMR `MachineModel` byte → display string. Belongs in `lib/state` next to `MACHINE_ERROR_TEXT` (n5), since it's pure decode logic. Not urgent.

### n7 — `web/src/lib/state/app.svelte.ts:535-548` has a comment block describing a function that isn't defined

The block describes `ImportShotResult` and references `importShotFile` 25 lines before the method itself. Just stale comment drift from when the method was probably nearby.

### n8 — Scale page's `Reset peak` / `Start timer` buttons are visible and clickable but do nothing

**Location:** `web/src/routes/scale/+page.svelte:200-211, 351-359`.

Marked `// TODO`. They look identical to working buttons, which is a UX trap (the user clicks Tare and it works; clicks Start Timer and gets no feedback). Either disable + tooltip "Coming soon" or hide until wired.

---

## Top 5 things actually worth fixing

Ranked by ROI — concrete wins per unit effort, not severity.

1. **m2 — extract `downloadBlob` + `timestamp` to `lib/utils/download.ts`.** 15 minutes. Collapses six copy-paste sites, including `ShotDetail.download` (m4). Pure mechanical refactor, zero risk.

2. **m5 — delete `CremaApp.setSuppressDe1Sleep`.** 5 minutes. A no-op method that misleads the caller. Pure deletion plus updating the one call site to drop a no-op `void` invocation.

3. **m3 — unify `De1State` / `ScaleState` and lift the state-label map into `lib/ble`.** 30 minutes. Removes ~25 lines of duplication and ensures the two screens can't drift apart in label wording.

4. **M1 + M2 together — drop unit suffixes on `Event` value-type fields and `ScaleCapabilities.standby_minutes`.** 1-2 hours including typeshare regen + the cascade of shell-side renames. It's the only outstanding violation of an otherwise well-enforced convention; doing it now while the surface is small is much cheaper than doing it later.

5. **M3 — decide on the unused read-path fields.** Either land doc-11's deferred UI surfaces (`machineError` banner, `idleSince` ticker, ShotSettings / loadedProfileShape readouts) or move them out of `UiSnapshot` into an opt-in side channel. Right now the snapshot is paying the spread-copy cost of plumbing nobody reads. The decision matters; the implementation either way is a couple hours.

Everything below the top 5 is FYI. The codebase is in honest-to-goodness good shape — the deferred-refactors doc already names the biggest pending items, the state layer is well-bounded, and the conventions are otherwise consistently applied.

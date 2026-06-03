# Crema Android Shell — Implementation Plan

A detailed, screen-by-screen plan to grow the Phase-0 Android scaffold into the
full 8-screen Crema tablet app, faithful to the Compose design handoff
(`~/code/compose-handoff`) and aligned to the web/PWA shell's business logic
(`crema/web`).

> **Status of this doc.** This is the *interpretation + sequencing* layer. The
> three sources of truth it stitches together are:
> 1. **Design / layout** — `compose-handoff/` (`DESIGN.md` for intent; `prototype/tablet/*.jsx`+`*.css` for pixel truth; `app/theme/`, `app/components/`, `app/screens/ScaleScreen.kt` for the canonical Compose port). **When prose and prototype disagree, the prototype wins.**
> 2. **Business logic** — the Svelte shell (`web/src/lib/**`). Same Rust core, same `applyCoreOutput` funnel; Effect-TS collapses to coroutines + `StateFlow` on Android.
> 3. **The contract** — the UniFFI `CremaBridge` (`core/de1-ffi/src/lib.rs`) and the typeshare types (`core/bindings/crema-core.kt`).

---

## 0. Where we start / where we're going

**Today (Phase-0, on `effect/phase-0-spike` lineage).** One `:app` module, one
`MainActivity` + `MainViewModel`, stock `MaterialTheme`. It connects to a DE1
and a Bookoo scale over Nordic BLE, feeds raw GATT bytes to `CremaBridge`,
folds the decoded `CoreOutput` events into a flat `MainUiState`, and renders a
debug readout. **It already drives the scale** (tare + every capability-gated
setting, two-way). It does **not** drive the machine, persist anything, or have
real navigation or the design system.

**Target.** The tablet app specced in `DESIGN.md`: a `NavigationRail` shell over
six destinations (Brew · Profiles · Beans · History · Scale · Settings) plus two
pushed editors (Profile, Bean), dark-by-default `CremaTheme`, capability-driven
throughout, with live telemetry on the Brew dashboard. Primary form factor is
**tablet / landscape, 1280×800 dp**. (A phone/compact design is explicitly a
later deliverable — see §6.)

**North-star architecture** (unchanged from `android/README.md`): one `:app`
module, one APK, **adaptive by window size class**, authored once per screen and
re-flowed. Decode lives in Rust; Kotlin marshals bytes and renders. Mirror the
web's **one funnel**: `CoreOutput → events (fold into state) → commands (execute
BLE writes)`, both in order.

### Golden rules (carry into every screen)
- **Dark is default.** `CremaTheme(forceDark = true)`; Light wired only via Settings → Display.
- **Telemetry colors never swap.** pressure = sage `#6B8C5F`, flow = blue `#4A6FA5`, temp = coral `#C44E3F`, weight = amber `#D89030`. (`Color.kt` already encodes the sage/amber swap correctly — keep it.)
- **Numbers are mono + tabular + united** (`CremaTheme.readout.*`, `tnum`).
- **Radius step-down** is the "feels native" lever (12dp card holds 8dp buttons; hero controls like TARE may match the card at 16dp).
- **Capability-driven, never device-driven.** Render a control only if the core's capability flag is set; otherwise an explanatory empty state, never a blank card. (`ScaleScreen.kt` is the exemplar.)
- **No machine/scale decode in Kotlin.** Always through `CremaBridge`.
- **Never invent chrome.** Every screen = `CremaNavigationRail` + a content `Column`/`Row`. Two-pane layouts put the detail pane on the right.

---

## 1. Foundations (build these before any screen)

This is the enabling layer. Roughly two weeks of work that unblocks everything.

### 1.1 Dependencies to add (`app/build.gradle.kts`)
Already present: Compose BOM `2026.04.01`, Material3, activity-compose, lifecycle-viewmodel-compose, kotlinx-serialization-json, Nordic BLE `2.0.0-alpha19`, JNA, UniFFI codegen.

Add:
- `androidx.navigation:navigation-compose` — the nav graph.
- `androidx.compose.ui:ui-text-google-fonts` — Newsreader / Hanken Grotesk / JetBrains Mono (the theme's `Type.kt` already references the GoogleFont provider). Add the certs array + GMS provider, **or** bundle static `.ttf` in `res/font/` and swap the `FontFamily` calls (see `compose-handoff/README.md` "Fonts").
- **Phosphor icons** — `com.adamglin:phosphor-icon` (vector) *or* the Phosphor font in `res/font/`. Bind once in `PhIcon(name)` (the stub in `CremaComponents.kt`); every screen references exact Phosphor glyph names as strings.
- `androidx.compose.material3.adaptive:adaptive*` — `WindowSizeClass`/`currentWindowAdaptiveInfo`, `ListDetailPaneScaffold` (History), `NavigationSuiteScaffold` (rail↔bottom-nav reflow). Tablet-first; compact is a later pass.
- **Persistence**: `androidx.room:room-runtime`+`room-ktx`+`room-compiler` (KSP) for history/beans/profiles queries, **and** `androidx.datastore:datastore-preferences` for app settings. (See §1.6 for the alternative "JSON-blob" approach.)
- `io.coil-kt:coil-compose` — bean bag photos.
- **Visualizer HTTP**: `com.squareup.okhttp3:okhttp` (or ktor). OAuth PKCE + uploads.
- ZIP: `java.util.zip` is built-in (beans `.crema.zip` / BC `.zip`, history capture `.zip`).
- `androidx.work:work-runtime-ktx` — background Visualizer upload/sync queue (the web's `UploadQueue` + 5-min daemon).

### 1.2 Theme + component library (port the handoff Kotlin verbatim)
1. Move `compose-handoff/app/theme/*.kt` → `coffee.crema.ui.theme` (`Color`, `Type`, `Shape`, `CremaTheme`). They compile against stock M3. Confirm green.
2. Bind `PhIcon` to the chosen Phosphor source. Wire fonts.
3. Move `compose-handoff/app/components/CremaComponents.kt` → `coffee.crema.ui.components`.
4. Move `compose-handoff/app/screens/ScaleScreen.kt` → `coffee.crema.ui.screens` and render it under `CremaTheme` to validate the pipeline against `DESIGN.md §3.1`.

**Components still to build** (specced in the prototype's `m3-components.jsx`; follow the same idiom and the radius/telemetry rules):
- `CremaQuickStepper` + `SplitLabel` — compact −/value/+ with optional preset chip row and a tap-the-dim-half label swap (Dose↔Grind, Yield↔Ratio, Pre-inf↔Flush). Brew Quick Controls + editors.
- `CremaTelemetryStepper` — big 56dp −/value/+ (already partially in `CremaStepper`).
- `CremaSortPill` — direction toggle + field dropdown.
- `CremaSplitButton` — primary action + caret menu of rich items (Beans "Add bean", History "Export"/"Download").
- `CremaFilterBar` — single-row chip strip that **measures** and overflows extras into a "More" menu, trailing controls pinned right. **The only non-trivial one** — implement with `SubcomposeLayout` (measure the full set, cut to fit, route the remainder into a `DropdownMenu`). Reserve ~116dp for the "More" button (matches the JSX `compute()`).
- `CremaOverflowMenu` — kebab → `DropdownMenu`; `danger` items in error color.
- `CremaModalBottomSheet` — wrap M3 `ModalBottomSheet` (Quick Controls); surface-container-low, 28dp top corners.
- `BrewChannelCard` — dual-channel readout card (primary+secondary value in channel colors, duotone icon, optional target).
- `ModeChip` — 56dp tonal pill, two-line label+sub, active/cancel state (Brew foot).
- `Eyebrow`, `CremaCard`, buttons, switch, segmented, chips, rail — already ported.

### 1.3 The core seam — promote `CremaBridge` into a `CremaCoreClient`
Mirror the web's `CremaApp` (`web/src/lib/state/app.svelte.ts`). One app-scoped owner of `CremaBridge` exposing **suspend** wrappers (hop to an IO dispatcher; `CremaBridge` is internally `Mutex`-guarded) and the single funnel:

```kotlin
fun applyCoreOutput(raw: String) {
    val out = json.decodeFromString(CoreOutput.serializer(), raw)
    out.events.forEach(state::applyEvent)   // fold → snapshot
    out.commands.forEach(::executeCommand)  // BLE writes, in order
}
```

This already exists inside `MainViewModel.onCoreOutputJson`; lift it out so both
BLE managers and every screen ViewModel share it. `executeCommand` must handle
**both** `Command.WriteScale` (works today) **and** `Command.WriteCharacteristic`
(no-op stub today — §1.4).

### 1.4 BLE layer — enable machine control (the big unlock; "AND5"/"AND6")
The transport already has `write(device, service, characteristic, data)` (used
for scale commands), and Nordic serialises GATT ops per device. What's missing:

1. **`De1Uuids`** — add the write-target characteristics:
   `A005` ReadFromMMR (`De1MmrRequest`), `A006` WriteToMMR (`De1MmrWrite`),
   `A00F` ShotHeader (`De1ProfileHeader`), `A010` FrameWrite (`De1ProfileFrame`),
   `A012` Calibration (`De1Calibration`). (Already have `A002`/`A00B`/`A011`.)
2. **`WriteTarget → UUID` map** (mirror web `uuidForWriteTarget`, `ble/de1.ts:328`).
3. **`De1BleManager.writeCharacteristic(target, bytes)`** → `transport.write(...)`.
4. **Wire `Command.WriteCharacteristic`** in `executeCommand` to call it (drop unmapped targets with a log, like the web).
5. **Subscribe more notify chars** in `startObserving`: add `WaterLevels A011`, `ShotSettings A00B`, `MMR A005` (the read path answers on the same char). Keep the single merged, ordered stream.
6. **Synthesize `De1FrameAck`** — the DE1 emits **no notification** on `FrameWrite`. After a successful `De1ProfileFrame` write, feed the just-written bytes back: `applyCoreOutput(bridge.onNotification(NotificationSource.De1FrameAck, data, now))`. This drives the upload state machine (load-bearing; web `app.svelte.ts:717`).
7. **Generic scale** — at scale connect, read `bridge.scaleUuids()` for the per-model `{service, weight_notify, command_write}` and `Scale::scan_uuids()`/`scaleScanUuids()` for the multi-vendor scan set instead of hardcoding Bookoo (`ScaleBleManager`). Subscribe `command_write` separately when it differs (Bookoo `ff12`).
8. **Reconnect with backoff** — port web `transport.runReconnectLoop` (exp backoff 500ms→30s, ~8 attempts; suppress on deliberate disconnect; re-resolve characteristics on a fresh connection — guard the **double-subscribe** bug; re-fire `queryScaleSettings` on scale recovery).
9. **Profile-download guard** — after `ProfileUploadCompleted`, wait **500 ms** before `requestMachineState(Espresso)` (DE1 firmware holds `ProfileDownloadInProgress`; a state request inside that window aborts the shot). Mirror web `ProfileSync.profileDownloadGuard`.
10. **Optional tick** — `bridge.onTick(now)` backstops stale-scale / eco-mode / upload-ACK timeouts. The web never calls it (notification-driven + host clocks). Decision: add a low-frequency (≈500 ms) tick while connected as a robustness belt, or follow web and rely on the two host clocks below. Start without it; add if upload-ack/stale edge cases bite.

**Host clocks** to add (the core is sans-IO): scale heartbeat at
`ScaleCapabilities.heartbeat_interval_ms` (Decent Scale LCD keep-alive); a
user-presence heartbeat (`pointer/key` activity, debounced to 60 s →
`setUserPresent(true)`, gated on the suppress-sleep pref).

### 1.5 State model — `AppState` (the `UiSnapshot` port)
Replace `MainUiState` with a richer immutable snapshot held in
`MutableStateFlow<AppState>` and folded by a **pure** `applyEvent(snapshot,
event): snapshot` (port `web/src/lib/state/ui-state.svelte.ts`; unit-testable
without Compose). Field groups: connection (DE1/scale `BleConnectionState`,
status, eventLog), machine (state/substate/phase/error/idleSince), live
telemetry (`latestTelemetry`, `shotTelemetry: List<TelemetrySample>` capped
~2000, `shotInProgress`, `shotElapsed`, `dispensedVolume`, `shotFrame`,
`completedShot`), scale (weight/flow/timer/caps/settings/identity), DE1
diagnostics/config readback (MMR map, firmware, calibration, shotSettings,
waterLevel), profile (`activeProfileName`, `activeProfileFingerprint`,
`profileUploadProgress`). Notable folds to keep: `Telemetry` merges latest
scale weight/flow into each sample (complete 4-channel rows) and only appends
while `shotInProgress`; `MachineStateChanged` reload-recovery (entering
`Espresso` with no in-progress shot flips it on); `ShotCompleted` keeps the
series on screen until next start.

Plus: an **`ActiveShot`** store (frozen bean + profile + dose + QC params for the
window between `ShotStarted` and `ShotCompleted`) and read-side derived facades
**`BrewContext`** (joins bean/profile/settings/active-shot/snapshot,
in-flight-wins) and **`MachineReadout`** (typed getters over the MMR map:
`flushTempC`, `ghcOn`, `heaterVoltage`, …).

### 1.6 Persistence layer
The web persists everything client-side (no server): localStorage JSON blobs +
IndexedDB for blobs. Two options on Android:

- **(A) Room + DataStore (recommended).** Room entities for `shots` (queryable
  history/stats/sync), `beans`+`roasters`, `custom_profiles` (+builtin
  overrides/hidden/activeId), `captures` (raw BLE bytes), `maintenance`;
  DataStore for `settings` and Visualizer tokens/sync-config. Better for the
  History stats strip, filters, and sync reconciliation.
- **(B) JSON-blob parity.** Mirror the web 1:1 — one DataStore/file per store,
  defensive coerce on read. Less mapping, but History queries get awkward.

Either way, **lean on the core for the domain shapes**: persist the typeshare
types (`Profile`, `Bean`, `Roaster`, `StoredShot` exist in `crema-core.kt`) and
use the FFI coerce/normalize helpers where they exist. The editor's
segment-based `CremaProfile` (web `profiles/model.ts`) is a shell concern — see
the two-representation note in §6. Keys/caps to copy from the web: history cap
300, captures/images pruned lazily at startup, soft-delete tombstones
(`deletedAt`) on beans/roasters/shots for sync.

Canonical business rules live in the Rust core (`de1-domain`): roast band
(1–3/4–6/7–10 = light/medium/dark), days-off-roast, freshness windows
(per-band best/ok/bad day thresholds), ratio, burn-down (debit dose from active
bean on completion), profile bounds (32 steps, 0–1023 ml). Prefer calling the
core over re-deriving in Kotlin.

### 1.7 Navigation shell
`NavHost` (navigation-compose) with the 8 destinations from `prototype.jsx`:
`brew` (start) · `profiles` · `beans` · `history` · `scale` · `settings` +
pushed `profile-edit?mode=` and `bean-edit?mode=&from=`. `CremaNavigationRail`
(already built) on every rail destination with the live DE1+Scale connection
pips wired to connect/disconnect. For adaptive reflow, wrap in
`NavigationSuiteScaffold` (rail on medium/expanded, bottom nav on compact) — but
author tablet-first; compact polish is deferred.

### 1.8 App scaffolding
Replace the single-screen `MainActivity.setContent` with `CremaTheme { AppNavHost() }`.
Keep the BLE permission flow (`BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`). Introduce a
small app graph (manual DI or Hilt) owning one `CremaCoreClient`, one
`NordicBleTransport`, the two BLE managers, and the repositories; per-screen
`ViewModel`s read `AppState`/repos and call `CremaCoreClient`. Keep the debug
event-log readout reachable behind Settings → Advanced → "Show debug panel".

---

## 2. Screens (build order)

Build order follows the handoff's recommendation (theme/components → Scale →
the rest, charts last) and dependency order (libraries before editors before
sync). Each screen below gives: **layout**, **components**, **data wiring**,
**interactions→core**, **states**, **canvas**, **gaps**, **acceptance**.

Pixel-exact dp/structure for every screen live in `prototype/tablet/<screen>.jsx`
+ `screens-v2.css` (density values are the tablet canon). This plan gives the
skeleton + the design↔logic↔FFI wiring that no single source doc contains.

---

### 2.1 Scale ✅ (align the exemplar) — `scale-screen.jsx` / `ScaleScreen.kt`
Already fully designed and ported. Work remaining is **wiring it to live state**,
not layout.

- **Layout:** rail | column { header, row[ left(readout hero + dose helper + recent activity) : weight 1, right settings panel : 372dp ] }. (See `ScaleScreen.kt`.)
- **Data wiring:** replace the screen's `BookooThemisCaps`/sim with `AppState`: `scaleCapabilities`, `scaleWeight`, live `device_*` settings, `scaleName`/meta (`Connected · {latency} · {battery}% · FW {fw}`). Map the screen's `ScaleCapabilities` data class onto the core's `ScaleCapabilities` (`reports_flow/timer`, `volume?`, `standby?`, `flow_smoothing`, `anti_mistouch`, `auto_stop`, `modes`, `can_beep`, `can_set_unit_grams`, `heartbeat_interval_ms`).
- **Interactions→core:** TARE → `tareScale()`; toggles/segments → `setScaleFlowSmoothing`/`setScaleAntiMistouch`/`setScaleAutoStop`/`setScaleVolume`/`setScaleStandby`/`setScaleUnitGrams`; Beep → `scaleBeep()`; Disconnect → `disconnectScale()`; "Reset peak" → `resetScalePeaks()`; "Start timer" → `startTimer()`. All optimistic; the live stream confirms (already the established pattern in `MainViewModel`).
- **States:** disconnected → empty states (header + settings-panel "No settings yet" + connect CTA); weight-only scale → settings panel empty state, never blank.
- **Acceptance:** connect a real Bookoo → name/FW/battery populate, weight ticks live, every capability row reflects + writes the device. Connect a weight-only scale → empty settings state.

---

### 2.2 Brew (dashboard) — the hero — `brew-screen.jsx`, `brew-header.jsx`
The product. The most complex screen; build its static layout early but leave
the live chart Canvas for last.

- **Layout:** rail | column { header, body grid[`248px | 1fr`], foot(split) }.
  - **Header (twin-block):** Profile block + 1px divider + Bean block (the web already shipped this — commit "brew header twin-block + searchable pickers"; design in `bean-header-explore.jsx` Option A). Each block: eyebrow, title (Newsreader), meta line(s), tap → searchable picker popover; bean block has freshness dot + pencil → bean editor. Optional center **mode banner pill** (Steaming/Hot water/Flushing + progress + Cancel). Right: **Quick Controls** pill → bottom sheet.
  - **Left column (248dp, scrolls):** Extraction timer card (mono `mm:ss.d`, 44/20dp at density), Ratio card (`1:x` live vs target), **Phase card** (proportional segment track with early-exit notches + auto-scrolling per-phase rows), **Limits** stack (Yield/Volume/Time, color-coded fill), Last-shot card (rating + Yield/Ratio/Time, hidden while running).
  - **Right column (1fr):** four **dual-channel readout cards** — PRESSURE+RESISTANCE, FLOW+WATER, COFFEE+WATER(temp), WEIGHT+FLOW — then the **live chart** card (fills remainder).
  - **Foot (split):** left meta cluster (Power · Machine state · Scale · Steam · Tank); right three **mode chips** (Steam/Hot water/Flush w/ sublabels + active/cancel) + wide **Coffee/Stop** button (filled copper; turns to Stop while running).
- **Data wiring (`BrewContext` + `AppState`):** profile/bean from active selections; timer from `shotElapsed`; ratio from `scaleWeight`/dose; phase from `shotPhase`+`shotFrame`; the four channel cards from `latestTelemetry` (`group_pressure`/`resistance`, `group_flow`/`dispensed_volume`, `head_temp`/`mix_temp`, `scaleWeight`/`scaleFlow`); foot meta from `machineState`, `MachineReadout` (steam temp), `waterLevel` (tank), scale name. Limits from QC params + targets + live values.
- **Interactions→core:** Coffee → `startShot(qc)` (the gated sequence: block if no active profile → lazy profile sync by fingerprint, **await `ProfileUploadCompleted`** → optional pre-shot flush → 500ms guard → `requestMachineState(Espresso)`); Stop → `requestMachineState(Idle)`; mode chips → `requestMachineState(Steam|HotWater|HotWaterRinse)`; Power → `requestMachineState(Sleep|Idle)`; cancel banner → `requestMachineState(Idle)`. `StopTriggered` (SAW/SAV/MaxTime) arrives with its own `WriteCharacteristic` — just dispatch it.
- **Quick Controls bottom sheet:** favorites strip (pinned profiles w/ mini curves + pinned beans w/ avatars), 6-up `CremaQuickStepper` grid with split labels (Dose↔Grind, Yield↔Ratio, Brew temp, Steam, Hot water, Pre-inf↔Flush), foot toggles: chart channel visibility (8) + shot behaviour (Stop on weight, Auto-tare, Pre-flush, Steam purge, Steam eco). Behaviour toggles → `setStopOnWeight`/`setAutoTare`/… ; targets → `setShotTargetWeight`/`applyProfileVolumeLimit`/etc. Channel toggles → local chart prefs (persist in settings).
- **States:** resting vs extracting differ across timer label, ratio, telemetry (zeros vs live), chart (no curves vs 4 curves + playhead), last-shot card (shown vs hidden), foot state text.
- **Canvas — live chart** (do last; shared widget, see §3.1): dual-axis (left 0–10 bar/flow, right 0–100 °C/g), 0.5 gridlines, channel-colored curves drawn only while running (pressure solid thickest, temp dashed on right axis), dashed playhead at `now`. Interpolate between samples over `CremaMotion.durChart` (500ms). Telemetry cards must **not reflow** during a shot (tabular figures).
- **On `ShotCompleted`:** persist a `StoredShot` (assemble from the event + buffered telemetry; see core gap in §4), debit bean burn-down, fire `shotCompleted` webhook, enqueue Visualizer push, persist the capture slice (`captureSliceJsonl`).
- **Gaps:** machine control (§1.4) must be done first. Optional core `completed_shot_json()` (§4).
- **Acceptance:** load a profile, tap Coffee on a real DE1 → profile uploads if stale, shot starts after the guard, four channels + chart animate live, phase track advances, Stop works, a History row is written.

---

### 2.3 Profiles (library) — `profiles-screen.jsx`, `profile-preview.jsx`
- **Layout:** rail | column { header (title + "N saved · M pinned" + search + Import + New profile), `CremaFilterBar` (All/Pinned · Roast group w/ counts · Tags group · trailing Sort pill Recent/Name/Dose), 3-col grid }.
- **ProfileCard:** pin/loaded badges, **pressure-curve preview** (Canvas, §3.3), name + bean + last-used, roast/tag pills, 4-up metric strip (Ratio/Dose/Temp/Pre-inf), actions (Load on Brew, duplicate, edit, overflow). The loaded profile gets the copper `is-active` treatment + "Loaded" pill. Trailing "New profile" ghost card.
- **Data wiring (`ProfileRepository`):** built-ins via `builtinProfiles()` (read-only, baked UUIDs) folded with overrides (pinned/lastUsed) + custom profiles, hidden built-ins removed. Filters: roast/tag/pinned/beverage + text. Sort: recent (active floats top-left)/name/dose. Counts + tag facets derived for the filter bar.
- **Interactions→core/state:** Load → set active profile (pushes targets into the core + triggers a fingerprint-gated upload when DE1 ready); pin; duplicate (`new_profile_id()`); edit → `profile-edit?mode=edit`; New → `profile-edit?mode=new`; Import (.tcl/.json/.jsonl → `importLegacyTclProfile`/`importV2JsonProfile`, then adopt with a fresh id); export single → `exportV2JsonProfile`, library → `.jsonl`.
- **Acceptance:** built-ins + custom render with correct previews; filter/sort work; load marks active and (if connected) uploads; import round-trips.

---

### 2.4 Profile editor (pushed) — `profile-edit-screen.jsx`
- **Layout:** rail | column { breadcrumb header (back · Profiles › Edit · Discard/Duplicate/Save), grid[`380px form | 1fr curve`] }.
- **Left form:** title, Bean, Notes, Roast segmented, Tags (input chips + add), Pin toggle, 2×2 Targets grid (Dose/Yield/Brew-temp steppers + computed Ratio).
- **Right curve editor (Canvas + pointer, §3.4):** pressure-profile chart with **draggable control points** (pressure solid sage, flow estimate dashed blue, 9-bar dashed reference; y 0–12 bar, x = cumulative time). Segments header (+ Add) + expandable `SegmentEditor` per segment: collapsed = name + summary; expanded = Type (Pressure/Flow) & Ramp (Smooth/Fast) toggles + 6-field grid (Target, Time, Temp w/ coffee/water swap, Volume w/ enable-dot, Exit w/ metric swap + enable-dot, Max w/ enable-dot). **Enable dots** gate optional fields (dot off → stepper at 0.4 alpha, non-interactive; tap dot to enable).
- **Data model:** the segment-based `CremaProfile`/`ProfileSegment` (web `profiles/model.ts`). Convert to the core wire `Profile`/`ProfileStep` on save (`toCoreProfile`/`fromCoreProfile` equivalents). Validate against profile bounds.
- **Interactions→core:** Save → persist + (if active & connected) `uploadProfile`. Drag a point → recompute segment `target`/`time`. "Start from template" → built-in.
- **Gaps:** **`profile_bounds_json` is not exported to FFI** (exists in `de1-domain`, exported via wasm). Add it for editor validation (§4). The two profile representations need a Kotlin port of `to/fromCoreProfile` (§6).
- **Acceptance:** edit a profile, drag the curve, toggle enable-dots, Save → valid wire `Profile` produced; on a connected DE1 it uploads and brews.

---

### 2.5 Beans (library + roasters) — `beans-screen.jsx`
- **Layout:** rail | column { header (Library eyebrow + Beans + counts + search + Import + **`CremaSplitButton` "Add bean"** {Quick add / Full editor / Import}), tabs (Bags · Roasters), filter bar + sort pill, content }.
- **Bags tab:** filter bar (status All/Active/Frozen/Archived/Favourite + Roast group) + sort pill (Freshest/Name/Roaster/Remaining), **sectioned** 3-col grid (Active/Frozen/Archived headers). `BeanTile`: avatar (roaster initial on brand color), roaster·name·origin, star, roast/frozen/tag pills, **freshness line** (color-coded days off roast), **burn-down bar** (remaining/size g), actions (Set active, duplicate, edit, overflow {Freeze/Archive/Delete}).
- **Roasters tab:** simple cards (avatar, name, location · active-bag count, overflow).
- **Quick-add modal:** Name* + Roaster* + Roasted-on (+ "Nd ago" hint) + Bag-size stepper w/ presets (200/250/340/454) + Open-full-editor / Save-&-make-active.
- **Data wiring (`BeanRepository`):** `beans` + `roasters` (tombstones filtered). `bagState` (active/frozen/archived), freshness from the core (`roast_freshness` per band), burn ratio `1 - remaining/bagSize`. Filters/sorts as web. Freshness colors: best=`#6B8C5F`, ok=`#DBA764`, bad=`#D26456`, none=dim.
- **Interactions→core/state:** Set active → active bean (drives Brew bean block + burn-down); CRUD; Import (BC `.zip` → `importBeanconquerorJson`; Crema `.crema.zip` → `importCremaJsonl`) returns an `ImportPlan` to commit; export (`exportCremaJsonl`/`exportBeanconquerorMainJson`).
- **Gaps:** `coerce_bean_json`/`coerce_roaster_json` not in FFI (used to normalize imports) — add them (§4) or normalize via the import-plan path.
- **Acceptance:** bags section + filter/sort; freshness + burn-down correct; set-active flows to Brew; BC import produces beans+roasters.

---

### 2.6 Bean editor (pushed, full page) — `bean-edit-screen.jsx`
- **Layout:** rail | column { breadcrumb header (back · Beans › Edit · Cancel/Save), grid[`280px TOC rail | 1fr scrolling main`] }.
- **Left rail:** bag-photo drop slot (avatar + camera overlay; Coil + image store), numbered TOC (01 Identity … 08 Notes) that smooth-scrolls main, required-fields help note.
- **Main:** 8 numbered `BeBlock` sections — Identity (Name*, Roaster*, Tags, Flags Active/Pinned/Decaf), Roast & mix (custom **1–10 RoastPicker** pip track + Light/Medium/Dark bands, Mix, Roast type), Dates (Roasted-on*, Opened-on, Frozen, Archived), Bag & Grind (Bag size stepper+presets, Remaining, Grinder, Grind), Origin (2-col free-text grid: Country/Region/Farm/Variety/Elevation/Processing), Tasting (star rating + notes), Buy again (URL), Notes.
- **Data wiring:** `Bean`/`Roaster`/`BeanOrigin` (web `bean/model.ts` ↔ core `de1-domain::Bean`). Photo → image store (Room blob/files). Roaster field resolves/creates a roaster.
- **Acceptance:** edit all 8 sections, photo persists, RoastPicker + freshness consistent with the Beans grid, Save round-trips.

---

### 2.7 History (shot log) — `history-screen.jsx`
- **Layout:** rail | column { header (Shot history + "synced to Visualizer" count + search + Import + **Export split button** {Community v2 .jsonl / Replayable .zip} + Compare), stats strip (6: Today/This week/Total/Avg yield/Avg time/Avg rating), filter bar (All profiles + per-profile + Tags; trailing Range segmented {Last 30 days/All time} + sort pill Date/Rating/Profile/Bean), **list-detail** split[`480px master | 1fr detail`] }. Use `ListDetailPaneScaffold`.
- **Master `ShotRow`:** time + "ago", **pressure sparkline** (Canvas, §3.3), profile·bean·duration, ratio+yield, star string, sync pip (up/local/pending).
- **Detail pane:** header (Download split button {Community v2 / Replayable} / Load on Brew / Save as profile / overflow {Share/Delete/Delete here & Visualizer}), the **chart in static mode** (reuse §3.1 with `running=false`), metric strip (Time/Peak pressure/Peak temp/Peak flow), editable star rating, editable Tasting-notes textarea, tags.
- **Data wiring (`HistoryRepository`):** `shots` (tombstones filtered, cap 300). Stats + peaks via the core (`peaksOf`). Sync state derived from `visualizerId` + the upload queue. Filters/sorts as web. `Load on Brew` sets the profile/bean as active; `Save as profile` mints a profile from shot params.
- **Interactions→core:** ratings/notes/tags persist; Import (.shot/.shot.json → `importLegacyTclShot`/`importV2JsonShot`); Export jsonl (`exportStoredShotAsV2Json` per line, Crema extras in `metadata.crema.*`) / zip (raw captures from the capture store, skip shots without bytes).
- **Gaps:** Visualizer **reconcile/signature** helpers not in FFI (§4) — needed for two-way shot sync; assembling `StoredShot` on completion (§4).
- **Acceptance:** stats + filters + sparklines; selecting a shot shows the static chart + editable notes; export produces valid jsonl; (with Visualizer) sync pips reflect state.

---

### 2.8 Settings — `settings-screen.jsx`
- **Layout:** rail | column-grid[`248px section nav | 1fr content`]. Three primitives first: `SetHead(eyebrow,title,sub)`, `SetGroup(title){…}`, `SetRow(title,sub){control}`; controls: `CremaSwitch`, `CremaSegmentedButton`, `SetSelect` (value + caret dropdown), `Stepper`, text input, button, status dot. Every section reuses these.
- **Sections (order):** **Machine** (machine hero w/ image/state/firmware + update card; Connection group; DE1 diagnostics w/ live counts; Peripherals: Scale re-pair + Grinder), **Brew defaults** (Targets + **Shot behaviour**: auto-tare, stop-on-weight, max duration, auto-purge, group flush, steam-eco), **Water & maintenance** (maintenance cards w/ burn-down bars + water chemistry + intervals), **Display & units** (Theme segmented incl. Auto → wire `CremaTheme`; density; units), **Sharing** (Visualizer account + what-to-sync + local export), **Calibration** (sensor offsets + routines; advanced warning), **Advanced** (telemetry, diagnostics/debug-panel, webhooks, developer/replay, **service-grade** heater voltage + machine reset, resets), **About**.
- **Data wiring:** app prefs ↔ `SettingsRepository` (DataStore); machine rows ↔ core (`setGhcMode`, `setFlushTemp`, calibration read/write, `setHeaterVoltage` behind a confirm, `set_steam_hotwater_settings`, MMR setters); Brew-defaults behaviour ↔ core `setAutoTare`/`setStopOnWeight`/`setMaxShotDuration`/`enableSteamEcoMode`; maintenance ↔ `maintenanceReadout`; diagnostics ↔ `AppState` (`de1Diagnostics`, machine state, notification counts); firmware ↔ `firmwareUpdateStatus()`; Visualizer ↔ OAuth/token/sync-config (§3.2).
- **States:** every machine/scale row is capability/connection-gated; destructive actions are plain error-colored text buttons.
- **Acceptance:** theme/density/units apply app-wide live; machine rows reflect + write the DE1 when connected; maintenance bars track water/shots; debug panel toggles the Phase-0 readout.

---

## 3. Cross-cutting subsystems

### 3.1 The chart widget (shared Canvas)
One `LiveChart` composable, two modes: **live** (Brew, animates from
`shotTelemetry`, playhead, interpolation 500ms) and **static** (History detail,
fixed series). Dual-axis (left 0–10, right 0–100), 0.5 gridlines, channel colors,
pressure solid/thickest, temp dashed on right axis, curves only when running.
Channel visibility from settings toggles. The single biggest custom-drawing
task — build after the static screens land.

### 3.2 Visualizer sync (the web `services/` + `effect/` stack → Kotlin)
Collapse Effect to coroutines + repositories. **OAuth 2.0 + PKCE** against
visualizer.coffee (`/oauth/authorize|token|revoke`, redirect to an app
deep-link/`auth/visualizer/callback`), token vault in DataStore with
proactive-refresh-within-5-min + refresh-on-401 + rotation. **Upload**: build the
v2 payload (Crema extras in `metadata.crema.*`), POST + soft PATCH (coffee_bag
link, tags, inline bean, rating→flavor×3), bind `visualizerId`. **Pull/reconcile**
by signature. **Upload queue** → WorkManager (3 attempts, offline-guarded, drain
on launch/online/foreground/5-min tick). Webhooks: fire-and-forget. Sync config
per-entity direction (off/backup/pull/two-way). **Behaviors to preserve, not the
Effect machinery.** Lower priority than the local screens.

### 3.3 Static curve canvases
- **Profile preview** (Profiles grid): Catmull-Rom→Bézier from a normalized
  `shape` key (rao/blooming/decline/classic/turbo/cold), pre-infusion band, mid +
  9/12 reference lines, flow/temp/pressure layered fills+strokes, legend. Port
  `profile-preview.jsx` exactly (300×96 viewBox, the SHAPES tables).
- **Shot sparkline** (History rows): a single 84×32 pressure path per shape, sage
  stroke. Port `history-screen.jsx` paths.

### 3.4 Profile curve editor (Canvas + pointer)
620×240 viewBox, x=cumulative time/total, y inverted 0–12 bar; draggable end-
points (`detectDragGestures`, origin fixed); flow estimate dashed; 9-bar dashed
reference. Recompute segment target/time on drag.

### 3.5 Import / export & captures
Profiles (.tcl/.json/.jsonl), beans (BC `.zip` / Crema `.crema.zip` with images),
history (Community v2 `.jsonl` / Replayable capture `.zip`), shots (.shot/.shot.json) —
all parse/serialize **in the core** via FFI; Kotlin handles file IO + zip
(`java.util.zip`) + the image store. Raw BLE captures recorded per session
(`BleSessionRecorder` already exists) → capture store, sliced on completion via
`captureSliceJsonl`.

---

## 4. Core / FFI work needed (flagged gaps)
These exist in `de1-domain`/`de1-app` (and are exported to **wasm** for web) but
are **not on the UniFFI `CremaBridge`** yet. Each is small — mirror the wasm export.

| Gap | Needed by | Effort |
|---|---|---|
| `profile_bounds_json()` | Profile editor validation (§2.4) | trivial — mirror wasm |
| `coerce_bean_json()` / `coerce_roaster_json()` | Bean import normalization (§2.5/2.6) | small (or use the import-plan path) |
| `completed_shot_json()` (assemble `StoredShot` from the just-recorded shot) | History save on completion (§2.2/2.7); avoids re-assembling in Kotlin | small–medium; optional |
| Visualizer `reconcile_shots` / `signature_for_shot` | Two-way shot sync (§2.7/3.2) | small; or re-implement in Kotlin (duplicates logic) |
| Firmware flash (`firmware_locks_writes` etc.) | Settings → firmware update (future) | deferred to core v2 |

**Already complete in the core (no work):** `request_machine_state`,
`upload_profile`/`cancel_profile_upload`, all steam/hot-water/MMR setters,
calibration reads, every scale method, `firmware_update_status`,
`builtin_profiles_json`, import/export of profiles & shots. The gating work for
machine control is **shell-side** (§1.4), not core-side.

---

## 5. Milestones

- **M0 — Foundations (§1).** Deps, theme+components, `CremaCoreClient`, `AppState` fold, persistence skeleton, nav shell, machine-control BLE path (§1.4). *Done when:* the app boots into `CremaTheme` with a working rail + the Scale screen on live state, and a `requestMachineState` round-trips to a DE1.
- **M1 — Brew (read-only) + Scale.** Full Brew layout + header pickers + readout cards on live telemetry; chart static placeholder. *Done when:* a live shot drives timer/ratio/phase/channels (no chart yet).
- **M2 — Machine control + chart.** `startShot`/stop/mode chips/Quick Controls + profile upload + the live `LiveChart`. *Done when:* tap Coffee brews end-to-end on real hardware and the chart animates.
- **M3 — Libraries + editors.** Profiles + Profile editor (curve), Beans + Bean editor. *Done when:* create/edit/load a profile and a bean flow into Brew.
- **M4 — History.** List-detail, stats, sparklines, static chart, import/export, captures. *Done when:* completed shots persist and replay/export.
- **M5 — Settings.** All 8 sections wired to prefs/core/maintenance/diagnostics.
- **M6 — Visualizer + polish.** OAuth, upload queue (WorkManager), sync, webhooks; reconnection hardening; compact/phone reflow pass (pending the phone design).

---

## 6. Risks & open decisions
- **Phone/compact form factor** is a *separate, forthcoming design* (user-stated). Author tablet-first; keep `NavigationSuiteScaffold` + size-class hooks so the compact reflow is additive, but don't gold-plate compact now.
- **Two profile representations.** The editor wants the segment-based `CremaProfile`; the core/wire wants step-based `Profile`. Port `to/fromCoreProfile` to Kotlin (a faithful adapter), or push the segment model into the core. Recommend the Kotlin adapter first (smaller core surface).
- **Persistence: Room vs JSON-blob parity.** Recommend Room+DataStore for History queries/sync; accept the mapping cost. Reuse core types as the serialized shapes.
- **`onTick` necessity.** Web omits it; the FFI says it backstops timeouts. Start without; add a 500ms connected tick if upload-ACK/stale-scale edge cases appear.
- **Fonts & icons.** Decide GoogleFont provider (needs GMS certs) vs bundled `.ttf`; decide Phosphor vector lib vs font. Both are one-time bindings (`Type.kt`, `PhIcon`).
- **Reconnection robustness** is currently absent (Phase-0 limitation). Port the web's backoff loop early in M0/M1 — it affects every screen's perceived reliability.
- **Capture/image storage growth.** Mirror the web's lazy prune-at-startup caps.

---

## 7. Quick reference — source map for the implementing agent
| Need | Look at |
|---|---|
| Pixel-exact layout/spacing/values | `compose-handoff/prototype/tablet/<screen>.jsx` + `screens-v2.css` (density = tablet canon) |
| Design tokens (port verbatim) | `compose-handoff/app/theme/` ⇄ `prototype/tablet/m3-tokens.css` |
| Reusable component contracts | `compose-handoff/app/components/CremaComponents.kt` ⇄ `prototype/tablet/m3-components.jsx` |
| Worked Compose screen | `compose-handoff/app/screens/ScaleScreen.kt` ⇄ `scale-screen.jsx` |
| Intent / rationale / voice | `compose-handoff/DESIGN.md` |
| Shell logic (connect/funnel/control/state) | `web/src/lib/state/app.svelte.ts`, `state/ui-state.svelte.ts`, `ble/{transport,de1,scale}.ts`, `services/*-orchestrator.ts`, `services/profile-sync.ts` |
| Data models & rules | `web/src/lib/{profiles,bean,history,settings}/model.ts`; canonical rules in `core/de1-domain/src/bean.rs` |
| FFI contract | `core/de1-ffi/src/lib.rs`; types `core/bindings/crema-core.kt` |
| Existing Android seam | `android/.../core/`, `ble/`, `ui/MainViewModel.kt`; `android/CONTEXT.md` + `README.md` |

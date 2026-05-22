# Settings page audit

## Methodology

Walked the canonical settings bundle in `web/src/lib/settings/store.svelte.ts`
field-by-field. For each of the 31 fields, located the UI control under
`web/src/lib/components/settings/sections/*.svelte` and the top-level page
`web/src/routes/settings/+page.svelte`, then grepped the entire `web/src` tree
(`*.svelte` + `*.ts`) for downstream reads of `prefs.<key>`, `settings.current.<key>`,
or the matching prop name. The four unit prefs got a second pass: every
`convert*` / `format*` helper consumer in `web/src/lib/settings/format.ts` was
verified, and every `°C`, `°F`, ` g`, ` mL`, ` bar`, ` ml/s`, ` psi`, ` oz`
literal was grepped to flag hardcoded readouts that bypass the helpers.
Read-only pass — no source files were modified.

## Headline counts

- Wired: **9**
- Partial: **5**
- Stub: **16**
- Wrong layer: **1**

(Total: 31 fields in `DEFAULT_SETTINGS`.)

## Summary table

| Setting | Section | Verdict | Notes |
|---|---|---|---|
| `theme` | Display & units | Wired | `data-theme` on `<html>`; charts re-render via `theme.svelte.ts` |
| `density` | Display & units | Stub | No consumer; segment writes localStorage and nothing else |
| `screensaver` | Display & units | Stub | No screensaver component exists |
| `weightUnit` | Display & units | Partial | Brew dashboard / LastShotCard / ShotRow / ShotDetail / Scale hero respect it; History stats strip, profile-meta line, ProfileCard, ProfileEditor steppers, BrewDefaults stepper all hardcode `g` |
| `tempUnit` | Display & units | Partial | Brew channel readouts, peak temp on Last/Detail respect it; BrewDashboard profile-meta + service-mode pills, BrewTempStepper, BrewDefaults stepper, ProfileEditor steppers, ProfileCurveEditor/Preview right axis, ProfileCard, WaterStepper, CalibrationSection sub-text all hardcode `°C` |
| `volumeUnit` | Display & units | Partial | Brew dashboard's water-tank + dispensed-volume readouts respect it; WaterStepper, BrewDashboard's `ml/s` flow readout, segment `ml/s` chrome do not |
| `pressureUnit` | Display & units | Partial | LastShotCard, BrewDashboard pressure channel, ShotDetail peak-pressure respect it; LiveChart/StaticShotChart/ProfileCurveEditor/ProfilePreview axes hardcode `bar`, SegmentRow hardcodes `bar`/`ml/s` |
| `defaultDoseG` | Brew defaults | Wired | Read in `BrewDashboard.paramSeed` when no profile is active |
| `defaultRatio` | Brew defaults | Wired | Multiplied by `defaultDoseG` for the default seed yield |
| `defaultBrewTempC` | Brew defaults | Wired | `paramSeed.brewTemp` when no profile active |
| `defaultPreinfusionS` | Brew defaults | Wired | `paramSeed.preinf` when no profile active |
| `stopOnWeight` | Brew defaults | Wrong layer | Field also lives on `CremaProfile` and `BrewParamState` (profile-side and per-shot UI). The Settings copy is never read |
| `autoTare` | Brew defaults | Wrong layer | Same: lives on the profile model and Quick Sheet; Settings copy is dead |
| `autoPurgeAfterSteam` | Brew defaults | Stub | No consumer |
| `groupFlushBeforeShot` | Brew defaults | Stub | No consumer |
| `shotStartTone` | Sound & feedback | Stub | No tone-playing code anywhere in the repo |
| `shotEndTone` | Sound & feedback | Stub | Same |
| `maintenanceReminders` | Sound & feedback | Stub | No consumer |
| `volumePercent` | Sound & feedback | Stub | No audio surface exists; the slider writes localStorage only |
| `autoConnectOnLaunch` | Machine | Stub | `app.svelte.ts` never reads it; the only auto-action on launch is profile re-upload after connect, not connect itself |
| `telemetryRateHz` | Machine | Wired | Forwarded into `LiveChart` for decimation |
| `lineFrequencyHz` | Machine | Wired | Pushed to core at construction (`createCremaApp`) and on change via `setLineFrequencyOverride` |
| `suppressDe1Sleep` | Machine | Wired | Drives the user-presence heartbeat loop in `createCremaApp` and a one-shot heartbeat in the connect handler |
| `visualizerAutoUpload` | Sharing | Stub | No upload pipeline exists; the section's TODOs say so |
| `visualizerPrivacy` | Sharing | Stub | Same |
| `visualizerIncludeProfile` | Sharing | Stub | Same |
| `visualizerIncludeNotes` | Sharing | Stub | Same |
| `showFlowCurve` | Advanced | Wired | Threaded through `LiveChart`; flow series is fed `null` when off |
| `showPuckResistance` | Advanced | Stub | No puck-resistance series rendered anywhere |
| `smoothPressure` | Advanced | Wired | Drives `LiveChart`'s rolling-mean smoother |
| `showDebugPanel` | Advanced | Stub | No debug / event-log panel binds to it (Scale page shows the event log unconditionally) |
| `shotExportFormat` | Advanced | Wired | Read by per-shot `ShotDetail.download` and bulk `routes/history/+page.svelte.exportAllShots` |

## Detailed findings

### 1. `theme`
- **Label / location:** "Theme" segment in Settings → Display & units.
- **Reads:** `lib/settings/store.svelte.ts:200-203` (applies `data-theme`), `lib/theme.svelte.ts:19-31` (MutationObserver signal), `LiveChart.svelte:30,453`, `StaticShotChart.svelte:17,239`, `ProfileCurveEditor.svelte:34,369`, `ProfilePreview.svelte:20,250`.
- **Verdict:** Wired.
- **Evidence:** Setting flips `data-theme` on `<html>`; CSS at `styles/tokens.css:236` swaps surface tokens; every uPlot chart re-renders via `theme.current`.
- **Recommendation:** Keep as-is.

### 2. `density`
- **Label / location:** "Density" segment in Settings → Display & units.
- **Reads:** None found outside the section.
- **Verdict:** Stub.
- **Evidence:** No CSS hook or component prop reads `prefs.density`. The control persists a value nobody consumes.
- **Recommendation:** Hide for now — wiring it requires picking a tokens strategy (CSS custom-property tier on `<html>`?) plus auditing every spacing token.

### 3. `screensaver`
- **Label / location:** "Screensaver" toggle in Settings → Display & units.
- **Reads:** None found.
- **Verdict:** Stub.
- **Evidence:** The advertised "calm pour animation after 10 minutes of inactivity" component does not exist in `web/src`.
- **Recommendation:** Hide for now (no animation asset/component exists).

### 4. `weightUnit`
- **Label / location:** "Weight" segment (g / oz) in Settings → Display & units.
- **Reads:**
  - `lib/components/brew/BrewDashboard.svelte:337,346,350` (live WEIGHT channel, shot yield, yield target).
  - `lib/components/brew/LastShotCard.svelte:32` (final yield).
  - `lib/components/history/ShotRow.svelte:65` (per-row yield metric).
  - `lib/components/history/ShotDetail.svelte:53,55` (yield + peak weight).
  - `routes/scale/+page.svelte:219` (scale hero readout).
- **Verdict:** Partial.
- **Evidence:** All five live readouts above respect it. The following hardcode `g`:
  - `routes/history/+page.svelte:411` (Avg yield stat — `<em>g</em>`).
  - `lib/components/profiles/ProfileCard.svelte:140` (Dose metric — `<em>g</em>`).
  - `lib/components/profiles/ProfileEditor.svelte:390,399` (Dose / Yield steppers `unit="g"`).
  - `lib/components/settings/sections/BrewDefaultsSection.svelte:34` (Default dose stepper `unit="g"`).
  - `lib/components/brew/DoseGrindStepper.svelte:29,40` (Dose stepper + chips `unit="g"`).
  - `lib/components/brew/YieldRatioStepper.svelte:25,36` (Yield stepper + chips `unit="g"`).
  - `lib/components/brew/BrewDashboard.svelte:423` (header profile-meta line — `${p.yield.toFixed(1)} g`).
  - `routes/scale/+page.svelte:350,388-395` (Tare button caption, dose-helper status lines).
- **Recommendation:** Implement — push every hardcoded `g` through `convertWeight` / `formatWeight`; also push every weight stepper (BrewDefaults, ProfileEditor, QuickSheet steppers) through a unit-aware input that converts on commit. Steppers are the biggest gap because they accept input in the wrong unit when the user picks oz.

### 5. `tempUnit`
- **Label / location:** "Temperature" segment (°C / °F) in Settings → Display & units.
- **Reads:**
  - `lib/components/brew/BrewDashboard.svelte:328,330,332,348` (TEMP, mix, steam channels, brew-target).
  - `lib/components/brew/LastShotCard.svelte:40` (peak temp).
  - `lib/components/history/ShotDetail.svelte:59` (peak temp).
- **Verdict:** Partial.
- **Evidence:** Hardcoded `°C` (or `°`) — none of these honour the pref:
  - `lib/components/brew/BrewDashboard.svelte:119-120` (mode-pill targets `'148 °C · 8 s'`, `'92 °C · 250 ml'`).
  - `lib/components/brew/BrewDashboard.svelte:175,181` (head-status meta strings).
  - `lib/components/brew/BrewDashboard.svelte:425` (profile-meta line `${p.brewTemp.toFixed(1)} °C`).
  - `lib/components/brew/BrewTempStepper.svelte:19,30` (stepper `unit="°C"`, chip row `unit="°"`).
  - `lib/components/brew/WaterStepper.svelte:30,41` (Hot-water temp stepper + chips).
  - `lib/components/settings/sections/BrewDefaultsSection.svelte:60` (Default brew-temp stepper).
  - `lib/components/profiles/ProfileEditor.svelte:408,455` (Brew temp + Tank temp steppers).
  - `lib/components/profiles/ProfileCurveEditor.svelte:214-223,244,457` (right-axis label/values, segment temp axis label).
  - `lib/components/profiles/ProfilePreview.svelte:146-155,178` (right-axis label/values).
  - `lib/components/profiles/ProfileCard.svelte:144` (Temp metric `<em>°C</em>`).
  - `lib/components/profiles/SegmentRow.svelte` (segment temp inputs — search shows `°` literals).
  - `lib/components/settings/sections/CalibrationSection.svelte:82` (sub-text — descriptive, less critical).
- **Recommendation:** Implement — every temp stepper / chip / chart axis needs `convertTemp` (display) plus the inverse on input commit. This is the most visible gap because the Brew header's `93.0 °C` and the QuickSheet's Brew-temp stepper are core-flow surfaces.

### 6. `volumeUnit`
- **Label / location:** "Volume" segment (ml / fl oz) in Settings → Display & units.
- **Reads:**
  - `lib/components/brew/BrewDashboard.svelte:369` (the inline `convertVolumeText` helper, used for both water-tank and dispensed-volume readouts).
- **Verdict:** Partial.
- **Evidence:** Hardcoded `ml` / `ml/s`:
  - `lib/components/brew/BrewDashboard.svelte:120` (`'92 °C · 250 ml'` mode-target string).
  - `lib/components/brew/BrewDashboard.svelte:527` (FLOW channel `unit="ml/s"` — but flow rate is a derived unit; if the user picks fl oz, the flow rate has no good answer).
  - `lib/components/brew/WaterStepper.svelte:48` (Hot-water volume stepper `unit="ml"`).
  - `lib/components/brew/SteamStepper.svelte:46` (`ml/s`).
  - `lib/components/profiles/ProfileEditor.svelte:430` (Max-total-volume stepper `unit="mL"`).
  - `lib/components/profiles/SegmentRow.svelte:66,130,156` (`ml/s` for the flow-mode segments and limiters).
  - Curve-editor / preview axis labels hardcode `ml·s`.
- **Recommendation:** Implement for *volume* readouts (water tank, max-total-volume, hot-water volume). For *flow rate* — leave as `ml/s` and add a one-line caveat to the Volume row sub: "Flow rates always shown in ml/s." Hard to convert sensibly to fl oz/s.

### 7. `pressureUnit`
- **Label / location:** "Pressure" segment (bar / psi) in Settings → Display & units.
- **Reads:**
  - `lib/components/brew/BrewDashboard.svelte:326` (live PRESSURE channel).
  - `lib/components/brew/LastShotCard.svelte:38` (peak pressure).
  - `lib/components/history/ShotDetail.svelte:57` (peak pressure).
- **Verdict:** Partial.
- **Evidence:** Hardcoded `bar`:
  - `lib/components/brew/LiveChart.svelte:10,309-320` (left-axis label as `bar / ml·s`).
  - `lib/components/history/StaticShotChart.svelte:7,140-151` (same).
  - `lib/components/profiles/ProfileCurveEditor.svelte:457` (`PRESSURE · bar` axis label).
  - `lib/components/profiles/ProfilePreview.svelte:117,134` (left-axis).
  - `lib/components/profiles/SegmentRow.svelte:66,130,156` (`bar` / `ml/s` per-segment).
  - `lib/components/settings/sections/CalibrationSection.svelte:100` (sub-text).
  - `lib/components/history/+page.svelte` — no "Peak bar" stat hardcode at the page level, but `lib/components/history/ShotDetail.svelte:242` reads `<div class="hi-metric-l">Peak bar</div>` (label hardcoded "Peak bar" even though the value `peakPressureM` is unit-aware — small cosmetic inconsistency).
- **Recommendation:** Implement for axis labels (LiveChart / StaticShotChart / ProfilePreview / ProfileCurveEditor) and SegmentRow. Charts are the highest-impact — they dominate the screen and freeze the unit even when the readouts swap.

### 8-11. `defaultDoseG`, `defaultRatio`, `defaultBrewTempC`, `defaultPreinfusionS`
- **Label / location:** Settings → Brew defaults → Targets (four steppers).
- **Reads:** `lib/components/brew/BrewDashboard.svelte:242-245` (the `paramSeed` fallback when no profile is active).
- **Verdict:** Wired (with the unit-respect caveats noted above for dose/temp).
- **Evidence:** When `activeProfile` is `undefined`, the Quick Sheet's `params` is seeded from these four prefs.
- **Recommendation:** Keep. Worth noting: the dose stepper accepts grams only (no conversion when the user picked oz) — fold into the `weightUnit` fix.

### 12. `stopOnWeight`
- **Label / location:** "Stop on weight" toggle in Settings → Brew defaults → Shot behaviour.
- **Reads:** None reference `prefs.stopOnWeight`. The name is reused as a **profile** field (`lib/profiles/model.ts:129,437,554`) and as a **Quick Sheet** param (`lib/components/brew/brew-params.svelte.ts:45,66`, `QuickSheet.svelte:101-103`, `ProfileEditor.svelte:469-471`); the Scale page even has its own local mirror (`scale/+page.svelte:157,541`).
- **Verdict:** Wrong layer.
- **Evidence:** This is genuinely a per-profile / per-shot setting (different profiles want different stop behaviour). Every read site uses the profile or the live brew-param store, never the app-pref copy.
- **Recommendation:** Remove from Settings. It's already on the profile model and the Quick Sheet; the third copy is dead weight that will mislead a future maintainer.

### 13. `autoTare`
- **Label / location:** "Auto-tare on start" toggle in Settings → Brew defaults → Shot behaviour.
- **Reads:** None reference `prefs.autoTare`. Same shape as `stopOnWeight`: lives on the profile model (`lib/profiles/model.ts:131`), on the Quick Sheet param (`brew-params.svelte.ts:47`), and as a local `autoTareLocal` on the Scale page.
- **Verdict:** Wrong layer.
- **Evidence:** Three independent copies elsewhere; the Settings one is unread.
- **Recommendation:** Remove. (Or, if you want a *global default* for new profiles, rename to `defaultAutoTare` and have `blankProfile()` seed from it — that would be a clean justification for keeping it.)

### 14. `autoPurgeAfterSteam`
- **Label / location:** "Auto-purge after steam" toggle in Settings → Brew defaults → Shot behaviour.
- **Reads:** None found.
- **Verdict:** Stub.
- **Evidence:** No post-steam logic exists. The mode-state machine in `BrewDashboard` has a `flushing` state but it's not triggered by a steam-end event reading this pref.
- **Recommendation:** Hide for now — needs a steam-end detector wired to a flush command (the DE1 has the command; the orchestrator does not yet have the post-steam trigger).

### 15. `groupFlushBeforeShot`
- **Label / location:** "Group flush before each shot" toggle in Settings → Brew defaults → Shot behaviour.
- **Reads:** None found.
- **Verdict:** Stub.
- **Evidence:** No pre-shot flush logic. The Brew page has no shot-start side-effects at all yet (the Start button is a UI stub per `BrewDashboard.toggleRun` TODO).
- **Recommendation:** Hide for now — coupled to the same shot-control surface that doesn't exist yet.

### 16. `shotStartTone`
- **Label / location:** Settings → Sound & feedback → first toggle.
- **Reads:** None found.
- **Verdict:** Stub.
- **Evidence:** No `Audio`, `<audio>`, `playTone`, or `AudioContext` reference exists in `web/src`.
- **Recommendation:** Hide for now — needs an audio asset/cue library.

### 17. `shotEndTone`
- **Label / location:** Settings → Sound & feedback → second toggle.
- **Reads:** None found.
- **Verdict:** Stub. Same as #16.
- **Recommendation:** Hide.

### 18. `maintenanceReminders`
- **Label / location:** Settings → Sound & feedback → third toggle.
- **Reads:** None found.
- **Verdict:** Stub.
- **Evidence:** Maintenance store (`lib/maintenance`) drives Water section UI, but no notification / chime emitter consults this flag.
- **Recommendation:** Hide.

### 19. `volumePercent`
- **Label / location:** Settings → Sound & feedback → Volume stepper (0-100 %).
- **Reads:** None found.
- **Verdict:** Stub.
- **Evidence:** No audio playback path exists.
- **Recommendation:** Hide (depends on #16-18 landing).

### 20. `autoConnectOnLaunch`
- **Label / location:** Settings → Machine → Connection → first toggle.
- **Reads:** None found in `app.svelte.ts` or anywhere else. The closest behaviour — `autoUploadActiveProfileOnReady` at `lib/state/app.svelte.ts:184` — only runs *after* an existing connection becomes `ready`; it does not initiate a connect.
- **Verdict:** Stub.
- **Evidence:** Web Bluetooth requires a user gesture to (re-)connect, so an actual "auto-connect on launch" is **architecturally not possible** in a browser PWA without a saved device + `navigator.bluetooth.getDevices()` (Chrome flag) and even then needs a gesture.
- **Recommendation:** Remove — the browser API can't honour it. If you keep it, repurpose to "Remember last device and offer a one-click reconnect prompt on launch."

### 21. `telemetryRateHz`
- **Label / location:** Settings → Machine → Connection → "Telemetry rate" select.
- **Reads:** `lib/components/brew/BrewDashboard.svelte:554`, `lib/components/brew/LiveChart.svelte:37,69,398,435` (decimates the buffer for the chart).
- **Verdict:** Wired.
- **Evidence:** The chart decimates incoming samples to the chosen rate. Note: the label says "How often the chart samples. Higher = smoother curves, more battery." — battery claim is misleading because the BLE notification rate is fixed; this only thins the *display* buffer. The current description is honest in the JSDoc (`store.svelte.ts:94` — "display only") but the UI sub-text overpromises.
- **Recommendation:** Keep, but soften the UI sub-text ("How often the chart redraws. Higher = smoother curves.").

### 22. `lineFrequencyHz`
- **Label / location:** Settings → Advanced → Telemetry → "AC mains frequency" select.
- **Reads:** `lib/state/app.svelte.ts:990` (one-shot push at construction), `lib/components/settings/sections/AdvancedSection.svelte:67` (push on change via `setLineFrequencyOverride`), `lib/core/index.ts:306,569` (core API).
- **Verdict:** Wired.
- **Evidence:** Forwarded into the wasm core's volume integrator. The Advanced section also polls `app.lineFrequencyHz()` once per second to surface the locked auto-detect value.
- **Recommendation:** Keep.

### 23. `suppressDe1Sleep`
- **Label / location:** Settings → Machine → Connection → "Keep DE1 awake while Crema is open" toggle.
- **Reads:** `lib/state/app.svelte.ts:208,796,1009`, `MachineSection.svelte:371-374` (`setSuppressDe1Sleep` + immediate `markUserPresent`).
- **Verdict:** Wired.
- **Evidence:** Drives the once-per-minute user-presence heartbeat in `createCremaApp` (line 1009) and a one-shot heartbeat in the connect handler (line 208).
- **Recommendation:** Keep.

### 24-27. `visualizerAutoUpload`, `visualizerPrivacy`, `visualizerIncludeProfile`, `visualizerIncludeNotes`
- **Label / location:** Settings → Sharing → Upload group.
- **Reads:** None outside the Sharing section.
- **Verdict:** Stub (all four).
- **Evidence:** The Sharing section's own JSDoc says so: "the Visualizer connection itself, signing in, the upload queue and history export need the external visualizer.coffee service the shell does not talk to." No HTTP client / OAuth code exists.
- **Recommendation:** Hide for now — keep the Sharing card UI (it sets honest expectations), but collapse the four upload-toggle rows behind a single "Connect Visualizer to configure uploads" CTA until the network layer exists.

### 28. `showFlowCurve`
- **Label / location:** Settings → Advanced → Telemetry → "Show flow curve in chart" toggle.
- **Reads:** `lib/components/brew/BrewDashboard.svelte:552`, `lib/components/brew/LiveChart.svelte:35,47,176,188,399,435`.
- **Verdict:** Wired.
- **Evidence:** When off, flow + setFlow series are filled with `null`, hiding the channel in the chart.
- **Recommendation:** Keep.

### 29. `showPuckResistance`
- **Label / location:** Settings → Advanced → Telemetry → "Show estimated puck resistance" toggle.
- **Reads:** None found.
- **Verdict:** Stub.
- **Evidence:** No "puck resistance" series exists in `UiSnapshot.shotTelemetry` or anywhere else; `LiveChart` has no resistance channel.
- **Recommendation:** Hide for now — needs (a) the core to compute the series from `pressure / flow` and (b) a LiveChart channel to render it.

### 30. `smoothPressure`
- **Label / location:** Settings → Advanced → Telemetry → "Smooth pressure curve" toggle.
- **Reads:** `lib/components/brew/BrewDashboard.svelte:553`, `lib/components/brew/LiveChart.svelte:36,49,59,132,139,399,436`.
- **Verdict:** Wired.
- **Evidence:** Runs a rolling-mean window over the pressure samples when on.
- **Recommendation:** Keep.

### 31. `showDebugPanel`
- **Label / location:** Settings → Advanced → Diagnostics → "Show debug / event-log panel" toggle.
- **Reads:** None found.
- **Verdict:** Stub.
- **Evidence:** The Scale page renders the event log unconditionally (`scale/+page.svelte:548-588`); no other surface conditionally renders on this pref.
- **Recommendation:** Implement — the JSDoc claims "the shared `UiSnapshot.eventLog` already exists", which is true, and a corner debug panel toggled by this flag is a small lift. Either implement, or remove. Right now it's pure UI noise.

### 32. `shotExportFormat`
- **Label / location:** Settings → Advanced → Diagnostics → "Shot export format" select.
- **Reads:** `lib/components/history/ShotDetail.svelte:116` (per-shot download), `routes/history/+page.svelte:73,327` (bulk export + tooltip).
- **Verdict:** Wired.
- **Evidence:** Per-shot Download and bulk Export both branch on this. Per-shot replay falls back to community when no IndexedDB capture exists.
- **Recommendation:** Keep.

## Unit-respect matrix

For each readout / input in the app × the four unit dimensions:
✅ honours the pref · ❌ hardcodes the canonical unit · — not applicable.

### Brew dashboard (`lib/components/brew/BrewDashboard.svelte`)

| Surface | Weight | Temp | Volume | Pressure |
|---|---|---|---|---|
| PRESSURE channel readout (l.519-523) | — | — | — | ✅ |
| FLOW channel readout (l.524-529) `ml/s` | — | — | ❌ | — |
| TEMP channel readout (l.530-536) | — | ✅ | — | — |
| WEIGHT channel readout (l.537-543) | ✅ | — | — | — |
| Yield card (l.467-477) | ✅ | — | — | — |
| Ratio card (l.478-484) | — | — | — | — |
| Volume card / water tank (l.493-498, 597) | — | — | ✅ | — |
| Header profile-meta (l.423-425) `g target · °C` | ❌ | ❌ | — | — |
| Mode head pill targets (l.117-122) `148 °C · 250 ml` | — | ❌ | ❌ | — |
| Mode head status meta (l.175,181) `... °C` | — | ❌ | — | — |
| Foot Group / Steam (l.587-592) | — | ✅ | — | — |
| Foot Scale (l.572-576) | ✅ | — | — | — |

### History (`lib/components/history/`)

| Surface | Weight | Temp | Volume | Pressure |
|---|---|---|---|---|
| ShotRow per-row yield (l.96-101) | ✅ | — | — | — |
| ShotDetail sub-line yield (l.196-199) | ✅ | — | — | — |
| ShotDetail Peak bar metric (l.241-243) — label hardcoded "Peak bar" but value is unit-aware | — | — | — | ✅ value / ❌ label |
| ShotDetail Peak temp (l.245-247) | — | ✅ | — | — |
| ShotDetail Peak wt (l.249-251) | ✅ | — | — | — |
| ShotDetail Yield (l.253-255) | ✅ | — | — | — |
| History stats: Avg yield (`routes/history/+page.svelte:411`) `<em>g</em>` | ❌ | — | — | — |
| StaticShotChart left-axis (`bar / ml·s`) | — | — | ❌ | ❌ |
| StaticShotChart right-axis (`°C / g`) | ❌ | ❌ | — | — |

### Scale page (`routes/scale/+page.svelte`)

| Surface | Weight | Temp | Volume | Pressure |
|---|---|---|---|---|
| Hero readout (l.322-328) | ✅ | — | — | — |
| Tare button caption (l.350) `0.0 g` | ❌ | — | — | — |
| Native flow `g/s` (l.339) | ❌ | — | — | — |
| Dose-helper status (l.385-398) `Add/Remove X.X g` | ❌ | — | — | — |
| Dose-helper target line (l.370-373) `target X.X g` | ❌ | — | — | — |

### Profile library / editor (`lib/components/profiles/`)

| Surface | Weight | Temp | Volume | Pressure |
|---|---|---|---|---|
| ProfileCard Dose metric (l.140) `<em>g</em>` | ❌ | — | — | — |
| ProfileCard Temp metric (l.144) `<em>°C</em>` | — | ❌ | — | — |
| ProfileEditor Dose stepper (l.386-394) `unit="g"` | ❌ (display + input) | — | — | — |
| ProfileEditor Yield stepper (l.395-403) `unit="g"` | ❌ (display + input) | — | — | — |
| ProfileEditor Brew temp stepper (l.404-412) `unit="°C"` | — | ❌ (display + input) | — | — |
| ProfileEditor Max-total-volume stepper (l.426-435) `unit="mL"` | — | — | ❌ | — |
| ProfileEditor Tank temp stepper (l.451-460) `unit="°C"` | — | ❌ | — | — |
| ProfileCurveEditor right-axis `°C` (l.214-223,244) | — | ❌ | — | — |
| ProfileCurveEditor left-axis `PRESSURE · bar` (l.457) | — | — | — | ❌ |
| ProfilePreview right-axis `°C` (l.146-155) | — | ❌ | — | — |
| ProfilePreview left-axis `bar / ml·s` (l.134) | — | — | ❌ | ❌ |
| SegmentRow `bar` / `ml/s` units (l.66,130,156) | — | — | ❌ | ❌ |

### Quick Sheet steppers (`lib/components/brew/`)

| Surface | Weight | Temp | Volume | Pressure |
|---|---|---|---|---|
| DoseGrindStepper (`unit="g"`) | ❌ (display + input) | — | — | — |
| YieldRatioStepper (`unit="g"`) | ❌ (display + input) | — | — | — |
| BrewTempStepper (`unit="°C"`, chips `unit="°"`) | — | ❌ (display + input) | — | — |
| WaterStepper temp (`unit="°C"`) | — | ❌ | — | — |
| WaterStepper volume (`unit="ml"`) | — | — | ❌ | — |
| SteamStepper (`unit="s"`, `unit="ml/s"`) | — | — | ❌ | — |
| PreinfFlushStepper (`unit="s"`) | — | — | — | — |
| LiveChart left-axis `bar / ml·s` | — | — | ❌ | ❌ |
| LiveChart right-axis `°C / g` | ❌ | ❌ | — | — |

### Settings page steppers (`lib/components/settings/sections/`)

| Surface | Weight | Temp | Volume | Pressure |
|---|---|---|---|---|
| BrewDefaultsSection Default dose stepper (l.34) `unit="g"` | ❌ | — | — | — |
| BrewDefaultsSection Default brew temp (l.60) `unit="°C"` | — | ❌ | — | — |
| BrewDefaultsSection Default pre-infusion (`unit="s"`) | — | — | — | — |

### Calibration (`lib/components/settings/sections/CalibrationSection.svelte`)

| Surface | Weight | Temp | Volume | Pressure |
|---|---|---|---|---|
| Temperature row sub-text (l.82) `offset, °C.` | — | ❌ (sub-text only) | — | — |
| Pressure row sub-text (l.100) `offset, bar.` | — | — | — | ❌ (sub-text only) |
| Calibration values (l.62 `fmt` — 3 decimals, no unit shown) | — | — | — | — |

## Top 5 most important unit-readout gaps

1. **Every weight stepper accepts/displays grams only.** ProfileEditor Dose / Yield, DoseGrindStepper, YieldRatioStepper, BrewDefaults Default dose, Scale dose-helper status all hardcode `g`. A user who picks `oz` in Settings still types grams everywhere they actually change a value — the segment selector creates a lie. Fix: a unit-aware stepper that converts between display unit and the canonical grams on commit.
2. **The BrewDashboard header profile-meta line ignores temp + weight units** (`BrewDashboard.svelte:423-425`): `Pre-inf {p.preinf}s · 1:{ratio} ratio · {p.yield.toFixed(1)} g target · {p.brewTemp.toFixed(1)} °C`. This is the most prominent text on the Brew screen.
3. **All four chart axis labels hardcode units** (`LiveChart`, `StaticShotChart`, `ProfileCurveEditor`, `ProfilePreview`): left axis says `bar / ml·s`, right says `°C / g`. The numeric *values* convert nowhere; pressure/temp/weight prefs can't be honoured on a chart without re-scaling the axes. Fix: thread `pressureUnit` + `tempUnit` + `weightUnit` into the chart axis formatters, recompute the tick values.
4. **BrewTempStepper + WaterStepper temp.** Quick Sheet's brew-temp control is a primary surface; it always shows °C regardless of the pref. Same for the Hot-water temp.
5. **History "Avg yield" stat + ShotDetail "Peak bar" label.** Stats strip (`routes/history/+page.svelte:411`) hardcodes `<em>g</em>` even though every per-row yield is unit-aware. ShotDetail metric strip (`l.241-243`) labels "Peak bar" while the value is unit-aware via `peakPressureM` — when the user picks psi the label and value disagree.

## Recommendations

### Implement these (real value, tractable scope)

- A unit-aware stepper component that owns the canonical→display conversion and the inverse on commit. Then replace every hardcoded `unit="g"` / `unit="°C"` / `unit="ml"` in ProfileEditor, DoseGrindStepper, YieldRatioStepper, BrewTempStepper, WaterStepper, BrewDefaults.
- Thread `pressureUnit`/`tempUnit`/`weightUnit` into `LiveChart`, `StaticShotChart`, `ProfileCurveEditor`, `ProfilePreview` (axis labels + tick formatter + the synthetic `×10` right-axis scaling).
- Fix the BrewDashboard header profile-meta line and the mode-pill target / status strings to use `formatTemp` / `formatWeight` / `formatVolume`.
- Fix the History stats strip's Avg-yield to use `formatWeight`. Rename ShotDetail's "Peak bar" label to "Peak pressure" (or derive it from `peakPressureM.unit`).
- Wire `showDebugPanel` — the JSDoc says the event log already exists in `UiSnapshot.eventLog`; a corner panel rendering it conditionally is a one-day job.

### Hide for now (toggle works in-store but advertised effect is unbuildable today)

- `density` — needs a tokens strategy.
- `screensaver` — no animation component exists.
- `autoPurgeAfterSteam` / `groupFlushBeforeShot` — depend on a shot-control surface that doesn't exist (the Start button is itself a TODO stub).
- `shotStartTone` / `shotEndTone` / `maintenanceReminders` / `volumePercent` — no audio layer exists.
- `visualizerAutoUpload` / `visualizerPrivacy` / `visualizerIncludeProfile` / `visualizerIncludeNotes` — no network layer / Visualizer integration.
- `showPuckResistance` — no resistance series or chart channel.

### Remove (pure dead UI, no path to making it work as labelled)

- `autoConnectOnLaunch` — Web Bluetooth's user-gesture requirement makes "auto-connect on launch" architecturally impossible in this PWA. Either remove or repurpose the row to "Show a reconnect prompt on launch" with `navigator.bluetooth.getDevices()`.
- `stopOnWeight` (Settings copy) — duplicates the per-profile + Quick-Sheet field. Delete the Settings copy.
- `autoTare` (Settings copy) — same; delete or rename to `defaultAutoTare` and have `blankProfile()` seed from it.

### Keep as-is

- `theme`, `defaultDoseG`, `defaultRatio`, `defaultBrewTempC`, `defaultPreinfusionS`, `telemetryRateHz` (soften the sub-text), `lineFrequencyHz`, `suppressDe1Sleep`, `showFlowCurve`, `smoothPressure`, `shotExportFormat`.

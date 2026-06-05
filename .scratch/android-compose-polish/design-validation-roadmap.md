# Android tablet design-validation roadmap

Status: IN PROGRESS (2026-06-04). Source: 8 parallel design-fidelity agents that
diffed each screen's `compose-handoff/prototype/tablet/*.jsx` + `screens-v2.css`
(v2 overrides win) + `m3-tokens.css` against the live Kotlin. Tablet 1280×800,
dark-first espresso. Tokens: `MaterialTheme.typography.*` (Newsreader serif for
display/headline/titleLarge; Hanken for the rest), `CremaTheme.readout.*` (mono
tabular), `CremaTheme.spacing.*` (4/8/12/16/24/32/48/64, edge=24), `CremaTheme.telemetry.*`,
`CremaMotion`. Verdict overall: current screens are functional **v1 skeletons**;
designs are substantially richer. Work proceeds in compile-safe waves.

## Foundation — DONE
- `PhIcon` now maps ~55 Phosphor names (regular weight). **Gotcha:** the weight
  group needs `import com.adamglin.phosphoricons.Regular` (extension prop) on top
  of per-icon `…regular.X` imports.
- `CremaReadoutType.readoutHero = mono(132,132,-0.038)` added (scale hero).
- Atoms expose `modifier`. Nav saves/restores tab state.

## Wave 1 — DONE (previews "janky" fix — the #1 complaint)
- `CanvasProfilePreview`: pressure stroke 2.4→1.8; flow @0.85α, fill 0.14→0.22;
  temp 1.6→1.2 @0.7α + dashed; margins 22/4→14/14; **stripped numeric axis chrome**
  (no more 0/6/12, 80/90/100, 10s labels) → clean card sparkline; one mid gridline
  + dashed 9-bar ref. Removed textMeasurer/labelStyle.
- `ProfilesScreen`: preview now in a `surfaceContainerLowest` well, clip medium, 96dp.

## NEW SHARED COMPONENTS/TOKENS still needed (build in CremaComponents or siblings)
- `CremaCard` **shape param** (default medium; Settings/Beans/History tiles want large=16dp).
- `Eyebrow` **letterSpacing** ≈0.07em (currently 0 — all-caps tracking missing).
- `StatTile(label,value,unit, container, valueStyle)` — History stats strip + metric tiles.
- `SparkChart(samples, modifier)` — 72×32 mini pressure polyline, sage, `surfaceContainerLowest` rounded-6.
- `StarRow(rating,max,size,interactive,onRate)` — History/Beans rating (regular Star + Fill.Star, primary tint).
- `SetRow(title,sub?,last){trailing}` + `SetGroup(title?){}` + `SetHead(eyebrow,title,sub)` + `SetSelect(value)` + `SetStepper(value:String,unit?)` + `StepBtn` + `SetMono` + `StatusDot(on)` + `SetSectionNav/SetNavItem` — Settings two-pane shell (G1-G10).
- `QcMiniToggle(checked,onColor,onCheckedChange)` — 26×15dp pill, used ~13× in QuickControls foot.
- `SegFieldCell` (compact 28dp stepper cell) + `TargetTile` (36dp stepper metric tile, copper Ratio variant) + `SegmentEditorCard` (accordion) — Profile editor.
- `BeanAvatar(name,color)` + `beanMark(seed):Color` + `BeanPill(text,variant)` + `BeanPreset` + `RoastPicker(value,onChange)` + `CremaOverflowMenu(items)` + `RoasterCard` — Beans.
- `ProfileMetric`/`ProfileMetricsRow` (4-up bordered mono grid) + `PreviewLegend` (3 chips overlay) + `LoadedPill` + `RoastPill`/Pill tone — Profiles cards.
- `ModeBannerPill` (Steam/HotWater/Flush header pill) + freshness→color map (`freshOk #DBA764`, `freshBad #D26456`) — Brew header + Beans.

## DATA-MODEL / VM BLOCKERS (defer; render visual-only or skip until wired)
- `StoredShot` lacks `rating/notes/tags/peakFlow` → History stars, notes, tags, avg-rating, peak-flow tile.
- `vm.updateBean` drops mix/roastType/favourite/decaf/openedOn/frozenOn/archivedAt/bagSize/remaining/tags/tastingNotes/url/region/farm/variety/elevation; `vm.addBean` drops bagSize.
- `SegmentEdit` lacks Volume/Exit/Max (+ enable-dots) → editor's 6-cell segment grid (only Target/Time/Temp real).
- Most Settings rows have NO VM state (only themeMode/autoTare/stopOnWeight/steamEco). Render static.
- QuickControls Pre-flush (`groupFlushBeforeShot`) + Steam purge (`autoPurgeAfterSteam`) not wired.
- Brand fonts: `Type.kt` binds `FontFamily.Serif/SansSerif/Monospace` — Newsreader/Hanken/JetBrains NOT applied (GoogleFont provider unwired; certs resource needed). App-wide identity gap.

## PER-SCREEN PRIORITIZED GAPS (condensed; full detail was in the agent reports)

### Brew (BrewScreen.kt) — mostly faithful, polish
1. right-col gap 9→8dp. 2. header Profile+Bean title weight 500→**400**, bean 18→20sp.
3. **ModeBannerPill** in header when service mode active (biggest new build).
4. Profile+Bean `tags` line (10.5sp 62%). 5. Bean freshness dot + "Nd off roast" in eyebrow-row.
6. Phase card should grow/internal-scroll (refactor, defer). 7. mode-chip cancel = 18dp circular badge inline.
8. foot meta = baseline Row not stacked Column; mono 400. 9. Tank unit ml vs mm (data). 10. chart head/legend (low).
Dropdowns (ProfileBlock:291, BeanBlock:373): **convert to ExposedDropdownMenuBox + menuAnchor(PrimaryNotEditable)** (fixes alignment), widthIn(min=200), item contentPadding(16,8), trailing caret-down. Needs `@OptIn(ExperimentalMaterial3Api)`.

### Profiles (ProfilesScreen.kt, CanvasProfilePreview.kt) — Wave 1 done; remaining card anatomy
- Legend overlay (Pressure/Flow dot, Temp dashed) + italic shape meta top-right.
- 4-up bordered **metrics grid** (Ratio/Dose/Temp/Pre-inf, mono, top+bottom hairline) replacing the 1-line recipe.
- Card **head row**: Loaded pill (left) + pin star (right). Action bar: Load (flex) + copy + pencil + overflow(⋮ Edit/Dup/Export/Delete). Name → titleLarge 22 (un-shrunk), icons OFF the name row.
- Grid Fixed(3) gap16 pad24/8; card radius large(16); active card = secondaryContainer + 1dp primary border; Loaded → outlined "Loaded on Brew". New-profile dashed tile. Search/filter bar (defer).

### Profile editor (ProfileEditScreen.kt, ProfileCurveChart.kt) — "doesn't match"; big rework
1. **Drop EditorCards** — design is flat (cards only for target tiles + segment cards).
2. Top bar: add **Discard changes** (text) + **Duplicate** (tonal copy) + relabel Save "Save profile"; bottom hairline.
3. **Segment accordion** (`SegmentEditorCard`): collapsed = number-pill + name + mono summary + caret; expanded = Type/Ramp toggles + 3-up field grid. Selected = 1.5dp primary ring. key(seg.id).
4. Segment fields = 3-up grid of `SegFieldCell` (hairline 1px sep) — Target/Time/Temp real now; Volume/Exit/Max deferred (model).
5. Left: serif **title** (name, headlineMedium 28), Bean input, Notes textarea (deferred persist).
6. **TargetTile** 2×2 (Dose/Yield/Temp + copper Ratio tile, 36dp steppers).
7. Curve header = serif "Pressure profile" + dynamic sub + Reset/Template. Chart in surfaceContainerLowest box, 280dp.
8. Editor curve: add dashed flow ghost; handle ring → surfaceContainerLowest.

### Beans (BeansScreen.kt, BeanEditScreen.kt) — skeletal; big rework
- List tile: **avatar + origin + roast/frozen pills + freshness dot + burn-down bar + action bar (Set-active pill + copy + edit + overflow)**; active = secondaryContainer + primary border; Fixed(3) gap16; section labels (Active/Frozen/Archived); Bags/Roasters tabs + filter/sort bar (defer-ish); header eyebrow + 3-part sub + Search + Import + Add split-button.
- Editor: **two-pane** (280 rail: photo + 01–08 TOC + help / main: numbered `BeBlock`s). RoastPicker(10 pips+bands), Mix/Roast-type segs, StarRating, tasting-notes, bag-size+remaining steppers+presets, tags, flags, origin 6-field, dates+frozen/archived switches, product URL. Most fields blocked on `updateBean` (see blockers).

### History (HistoryScreen.kt) — skeletal; big rework
1. Shot row → **flat 7-col grid** (time / spark / name+sub / ratio / yield / stars / pip), transparent, active=12%primary + 2px inset bar; gap 2dp. (drop per-row CremaCard).
2. **Stats strip** 6 `StatTile` (Today/Week/Total/Avg yield/Avg time/Avg rating) under header.
3. **SparkChart** in rows. 4. row stars + ratio/yield metric cols.
5. **Detail head** (mono date eyebrow, serif profile title, meta, action cluster).
6. Metric strip → 4 tiles below chart (Time/Peak P/Peak T/Peak flow). 7. master 320→480dp, gap16; detail in card; chart surfaceContainerLowest 300dp.
8. rating row, notes, tags (blocked on StoredShot fields). 11. header eyebrow + copy.

### Scale (ScaleScreen.kt) — exemplar; targeted
1. Unit "grams" → absolute bottom-right of readout card, bodyLarge 28sp onSurfaceVariant.
2. Hero numeral → **readoutHero** token (was inline 132f w/ stale 96 lineHeight).
3. Reset-peak/Start-timer → flat 48dp `surfaceContainerHigh` shapes.medium pills (`ScalePillButton`), not M3 Tonal.
4. Dose helper: "Switch profile" (shuffle), "Add 0.5 g" (primary), target marker on bar, bold-primary profile name.
5. minor: tare value Row(mono + small dim "g"); connect button 52dp/large. Perf: collect only weight in the readout leaf.

### Settings (SettingsScreen.kt) — v1 stub → two-pane 8-section rebuild
- G1 **two-pane shell** (248dp `SetSectionNav` + 880-max content). G2 nav items (48dp pills, secondaryContainer active). G3 `SetGroup` divider-rows (large card, per-row 16×20 + bottom divider). G5 `SetHead`. G6 `SetRow` (the workhorse — unlocks ~45 rows). G7 `SetSelect`. G8 compact `SetStepper`. G9 Machine hero + firmware callout. G10 mono readouts + status dots. G11 maintenance progress rows. Sections: Machine / Brew defaults / Water&maint / Display&units / Sharing / Calibration / Advanced / About. Most values static (no VM). Theme labels Light/Dark/Auto (align ids w/ VM).

### QuickControls (QuickControlsSheet.kt) — placeholder → rebuild (keep ModalBottomSheet)
1. **6-up stepper grid** (extend CremaStepper w/ presets + split-label). 2. dropdowns→ExposedDropdownMenuBox (shared w/ Brew). 3. favorites strip (profiles+beans chips). 4. header subtitle + Save preset/Reset/✕. 5. chart toggles → 4 grouped `QcMiniToggle` pairs (not 8 Switch rows). 6. shot toggles → mini-pills, add Pre-flush+Steam-purge, reorder (Stop-on-weight first).

## SUGGESTED WAVE ORDER (compile-safe batches, hand off each for build)
1. ✅ Foundation icons + readoutHero + preview janky fix.
2. ✅ Brew: both dropdowns→ExposedDropdownMenuBox+menuAnchor+caret+widthIn (alignment fix); right-col 8dp; Profile/Bean titles 400-weight + bean 20sp; foot meta→baseline Row; mode-chip cancel→circular badge; +cloud/stop icons. (Scale targeted folded into a later wave.)
3. ✅ Scale targeted (readoutHero, grams bottom-right, ScalePillButton, tare mono+g, connect 52dp).
4. ✅ Profiles card anatomy (4-up metrics grid, LOADED badge, outlined Loaded button, active border, Fixed(3), distinct roast pill, name 22, action bar). CremaCard gained shape+border params. Deferred: legend overlay, pin star (needs vm.togglePinned), bean subtitle (no field), new-profile tile, search/filter.
5. ⏳ Profile editor: ✅ top-bar Discard/Duplicate/Save-profile + divider, serif "Pressure profile" curve header + sub + Reset, pin copy, per-segment cards (surfaceContainerHigh) w/ number pills. ✅ accordion collapse/expand (number pill + collapsed summary + rotating caret, one open at a time). ⏳ DEFERRED: SegFieldCell 3-up grid (Volume/Exit/Max — needs SegmentEdit widening), TargetTile, flatten section EditorCards, serif title/Bean/Notes fields, dashed flow ghost.
6. ✅ Settings: two-pane shell — 248dp SetNavItem section-nav (8 sections, rememberSaveable active) + content pane (max 880) with SetHead + SetGroup + SetRow molecules. Real wired controls: Machine connect, Brew-defaults toggles, Display theme, Advanced debug, About. Honest ComingSoon placeholders for Water/Sharing/Calibration (need core/VM). ⏳ DEFERRED: Machine connection-hero + DE1 diagnostics readouts, Brew dose/ratio/temp default steppers, SetSelect/SetStepper/StatusDot molecules, the full ~60-row spec.
7. ⏳ History: ✅ Library eyebrow + "Shot history" copy, 4-tile StatsStrip (Shots/Avg yield/Avg time/Avg ratio), 480dp list, flat shot rows (name/ago + ratio/yield, selected 12% primary tint), detail head (date eyebrow + serif title + recipe meta). StatTile is History-private (promote to shared if reused). ⏳ DEFERRED: sparkline rows, row stars + metric-tile strip below chart, rating/notes/tags (StoredShot lacks rating/notes/tags/peakFlow), header search/import/export/compare.
8. ⏳ Beans: ✅ list — Library eyebrow + "N bags · N roasters · N active" sub, Fixed(3) grid, reworked tile (roaster eyebrow + 19sp name + origin.country, roast/Frozen/Decaf/tag pills, freshness line, active secondaryContainer+primary border, outlined "Active for brew", Set-active+edit+delete bar). ✅ EDITOR: full two-pane BeanEditScreen rebuild (left rail = photo placeholder + 8-item TOC + help; right = 8 numbered blocks Identity/Roast&mix/Dates/Bag&Grind/Origin/Tasting/Buy/Notes; RoastPicker 10-pip+bands, StarRating, BeFlag flags, bag-size presets, Mix/Roast-type segs, full origin) — wired to a refactored `vm.updateBean(id, roaster) { transform }` so EVERY field persists (core `Bean` already modeled them all → zero FFI/core change, proving the "no core needed" point). ✅ list avatar (roaster-mark square) + burn-down bar (remaining/bagSize). ⏳ DEFERRED: Bags/Roasters tabs + filter/sort bar, richer quick-add modal, TOC scroll-to-section, real photo upload.
9. QuickControls rebuild.
10. ✅ brand fonts via GoogleFont provider (res/values/font_certs.xml from android/compose-samples; Type.kt Newsreader/Hanken/JetBrains via GoogleFont, system fallback w/o GMS). ✅ History: StoredShot rating/notes + vm.updateShot + detail star-rating + notes field. ✅ Settings Machine diagnostics (scale battery/firmware). ⏳ REMAINING: editor segment Volume/Exit/Max cells (SegmentEdit widening — Exit/Max are complex core shapes), QuickControls 6-up steppers (active-profile save plumbing).

# Punchlist — Holistic review: three shells + core

Date: 2026-06-13 · Branch at review: `effect/phase-0-spike`

Scope: **Web** (SvelteKit/Svelte 5), **Android-tablet** (`ui/screens`), **Android-phone**
(`ui/phone`), all over the shared Rust **core** (WASM for web, UniFFI for Android).
Reviewed with `rust-best-practices`, `effect-ts`, `svelte5-best-practices`,
`compose-expert`, and `interface-design:audit`.

**Root cause that connects most items:** core's domain helpers are exported to the
**WASM** binding but not the **UniFFI** binding, so Android is forced to hand-roll Kotlin
copies that have drifted (and phone then copied those again). Fixing the binding
asymmetry (Theme 1) collapses a large share of the rest.

Priority order in this file: **T1 (core/drift) → T2 (wiring) → T5 (core-internal) →
T4 (consolidation) → T3 (design)**.

Severity: `[P1]` correctness / broken / user-visible drift · `[P2]` maintainability /
latent drift · `[P3]` polish. ✅ = source-verified during review.

Counts: T1 ×11 · T2 ×6 · T5 ×8 · T4 ×17 · T3 ×12.

---

## Theme 1 — Refactor into core (avoid drift)

> The UniFFI binding (`core/de1-ffi/src/lib.rs`) is missing ~30 domain helpers that the
> WASM binding (`core/de1-wasm/src/lib.rs`) exposes. Each export below is a thin
> `#[uniffi::export]` wrapper around a `de1-domain` fn that already exists (unless noted
> "new in core"). Land the exports, then delete the Kotlin reimplementations.

- [ ] **T1-01 `[P1]` ✅ Export `brew_ratio` via UniFFI; replace 14 inline ratio sites.**
  - **Problem:** Web routes through `brew_ratio` (`web/src/lib/utils/ratio.ts:22`); Android
    never calls core and hand-rolls `yield/dose` in 14 places with inconsistent precision
    (`%.1f` in 9 sites, `%.2f` in 8). Same shot reads `1:2.40` (tablet brew) vs `1:2.4`
    (phone). Core `brew_ratio` is WASM-only (`core/de1-wasm/src/lib.rs:257`).
  - **Android sites:** `ui/screens/BrewScreen.kt:923,937,1170`, `HistoryScreen.kt:763`,
    `ProfilesScreen.kt:411`, `ProfileEditScreen.kt:595`, `QuickControlsSheet.kt:232`,
    `ui/phone/PhoneBrewScreen.kt:475,669,766`, `PhoneHistoryScreen.kt:311,706`,
    `PhoneProfilesScreen.kt:279`; existing `profiles/CremaProfile.kt:56 (val ratio)`.
  - **Fix:** add `#[uniffi::export] fn brew_ratio(dose, yield_out) -> Option<f32>` to
    `de1-ffi`; add one Kotlin `formatRatio(dose, yield): String` (1 decimal, em-dash on
    null) in a shared util; route all 14 sites + `CremaProfile.ratio` through it.
  - **Verify:** `grep -rn "/ *dose\|yield.*/.*dose" android/.../ui` returns 0 ratio math;
    every screen shows `1:2.4` (1 decimal).

- [ ] **T1-02 `[P1]` ✅ Export `roast_band` / `days_off_roast` / `roast_freshness` via UniFFI — fixes a correctness bug.**
  - **Problem:** core `roast_freshness` is **band-aware** (`core/de1-domain/src/bean.rs:99-187`:
    Dark best 4–10d / bad 15+, Medium best 6–14d / bad 22+, Light best 10–24d / bad 36+).
    Android `freshnessColor(frozen, days)` is **band-agnostic** — one threshold for all
    roasts (peak 4–21d). A dark-roast bean at 16d reads **stale in core/web but "peak
    green" on Android**. All three core fns are WASM-only (`de1-wasm:849,862,883`).
  - **Android sites:** `beans/BeanFormat.kt:23 (roastBand)`, `:55 (daysOffRoast)`;
    `freshnessColor` triplicated: `ui/screens/BeansScreen.kt:528`, `BrewScreen.kt:1497`,
    `ui/phone/PhoneBeansScreen.kt:412`; phone `RoastPicker` hardcodes a 3rd band threshold
    `PhoneBeanEditScreen.kt:417-421`.
  - **Fix:** export the three fns + a `roast_freshness`→color helper (see T1-09/T4-12) via
    UniFFI; delete `BeanFormat.roastBand`/`daysOffRoast` reimpls; replace all three
    `freshnessColor` copies with one helper driven by core's band-aware verdict.
  - **Verify:** a dark-roast bean (level ≥7) at 16 days shows the same status/color on web,
    tablet, and phone.

- [ ] **T1-03 `[P1]` ✅ Export `profile_bounds_json` via UniFFI; replace hardcoded editor limits.**
  - **Problem:** core caps (`core/de1-domain/src/profile_bounds.rs`): frame time **25.5s**
    (`u8p4` ceiling), brew/seg temp **100°C**, pressure/flow **15.9375**, volume **1023ml**.
    Android editor lets you author **120s** frames (`ProfileEditScreen.kt:286`) and **105°C**
    (`:288,471`) — firmware-invalid (saturates on the wire); and is conversely *stricter*
    on pressure (**12** vs 15.9375, `:431,469,etc.`). Web uses `profile_bounds.ts:43` →
    `profile_bounds_json`. Core fn is WASM-only (`de1-wasm:971`).
  - **Fix:** export `profile_bounds_json` via UniFFI; replace the literal min/max args in
    `ProfileEditScreen.kt` (and `PhoneProfileEditScreen.kt` counterparts) with values parsed
    from the core bounds JSON. Editorial "warn above X" stays shell-side per the module doc.
  - **Verify:** Android editor max frame time = 25.5s, brew temp = 100°C, pressure = 15.9375;
    matches web.

- [ ] **T1-04 `[P1]` ✅ Web `canonicalToDisplay` hardcodes conversion constants — route through WASM.**
  - **Problem:** `web/src/lib/settings/format.ts:219-229` open-codes `/28.3495`, `*1.8+32`,
    `*14.5038`, `/29.5735` while its siblings (`convertWeight/Temp/Pressure/Volume`,
    `:76-124`) and its own inverse `displayToCanonical` (`:233-244`) correctly call the WASM
    helpers (`grams_to_oz`, `celsius_to_fahrenheit`, `bar_to_psi`, `ml_to_fl_oz`). Fork from
    the single source of truth inside one file.
  - **Fix:** rewrite `canonicalToDisplay` to delegate to the same WASM helpers.
  - **Verify:** no literal conversion constant remains in `format.ts`; round-trip
    `displayToCanonical(canonicalToDisplay(x)) ≈ x`.

- [ ] **T1-05 `[P1]` Generate web `crema-core.ts` from `core/bindings/` + CI freshness check.**
  - **Problem:** `web/src/lib/core/crema-core.ts` (~1043 lines) is a hand-maintained stale
    copy of generated `core/bindings/crema-core.ts` (~1602 lines), missing 13 types
    (`Bean`, `BeanMix`, `BeanOrigin`, `BeanRoastType`, `Roaster`, `ShotBean`, `WeightUnit`,
    `MaintenanceState`, `MaintenanceReadout`, `LocalShotRef`, `ReplayMeta`, `ReplayMetaBean`,
    `WireShot`, `De1Uuids`). Several are re-declared by hand:
    `maintenance/store.svelte.ts:55,153`, `visualizer/shot-sync-signatures.ts:48`,
    `bean/model.ts`, `history/model.ts`. A new Rust field silently won't appear web-side.
  - **Fix:** make `web/src/lib/core/crema-core.ts` re-export (or be generated from)
    `core/bindings/crema-core.ts`; delete the hand re-declarations; add a CI step that fails
    if the generated binding is newer than the checked-in copy.
  - **Verify:** CI fails on a stale binding; `MaintenanceState`/`WireShot`/`Bean` imported
    from one place.

- [ ] **T1-06 `[P2]` Export bean/roaster wire converters via UniFFI; delete Android hand-built wire.**
  - **Problem:** `bean_to_wire`/`bean_from_wire`/`roaster_to_wire`/`roaster_from_wire`/
    `roast_level_to_wire`/`roast_level_from_wire` are WASM-only. Web uses them
    (`bean/visualizer-sync.ts:176-212`); Android hand-builds the wire object
    (`visualizer/WireShot.kt:93-107`) and inline-bean PATCH (`VisualizerSync.kt:349-358`),
    already divergent (emits `roastLevel`/`roastedOn` as `JsonNull`).
  - **Fix:** export the six fns via UniFFI; replace the Android hand assembly.
  - **Note:** lower urgency until Android bean-sync ships, but the wire is already emitted
    today, so the divergence is live.

- [ ] **T1-07 `[P2]` Export `sub_state_error_message` (+ `is_recoverable`, `machine_model_name`, `has_cup_warmer`) via UniFFI.**
  - **Problem:** web shows human-readable machine errors via `ui-state.svelte.ts:630` →
    `sub_state_error_message`; Android shows the raw substate string
    (`MainViewModel.kt:2690`). All four are WASM-only.
  - **Fix:** export the four (one-line wrappers); wire Android to show readable error text
    and use `machine_model_name`/`has_cup_warmer` in the About/Settings readout.

- [ ] **T1-08 `[P2]` Add core `default_brew_defaults_json()` (or constants); both shells read it.**
  - **Problem:** dose 18g / ratio 2.0 / temp 93°C / preinf 8s are hardcoded independently in
    web (`settings/store.svelte.ts:226-228`) and Android (`MainViewModel.kt:243-246` +
    fallback literals `:833,835`). Core has a `BrewDefaults` struct (`crema_profile.rs:159`)
    but supplies no default *values*.
  - **Fix:** add `default_brew_defaults_json()` to core, export via both bindings, read from
    both shells.

- [ ] **T1-09 `[P2]` Add `roast_band5` (5-band display label) to core + both bindings.**
  - **Problem:** identical 5-bucket mapping hand-rolled twice — web `bean/model.ts:309-317`
    and Android `BeanFormat.kt:38`. Not in core.
  - **Fix:** add `roast_band5` to `de1-domain` next to `roast_band`; export via WASM + FFI;
    replace both shell copies.

- [ ] **T1-10 `[P2, latent]` Proactively export the bean/roaster sync surface via UniFFI.**
  - **Problem:** `reconcile_beans`/`reconcile_roasters`/`signature_for_bean`/
    `signature_for_roaster`/`coerce_bean`/`coerce_roaster` are WASM-only. Web uses them
    (`services/bean-sync.ts:287,371`); Android bean sync is "web-only for now"
    (`VisualizerSync.kt:33-36`). When Android adds bean sync it will be forced to re-port.
  - **Fix:** export now so the future Android path routes through core from day one.

- [ ] **T1-11 `[P3]` Wire Android profile-fingerprint upload-skip.**
  - **Problem:** `profile_fingerprint` is already on **both** bindings, but Android "v1 always
    uploads (no fingerprint-skip)" (`MainViewModel.kt:1255`). No drift — just a missing
    optimization that web has.
  - **Fix:** compute the fingerprint and skip re-upload of an unchanged profile.

---

## Theme 2 — Wiring completeness

- [ ] **T2-01 `[P1]` ✅ Remove false "Not implemented yet" pills from CalibrationSection.**
  - **Problem:** Temp/Pressure/Flow calibration rows carry `notImplemented`
    (`web/src/lib/components/settings/sections/CalibrationSection.svelte:379,419,459`) whose
    tooltip says *"no part of the app reads it yet"* — but the Apply/Reset handlers call
    `app.writeCalibration` / `resetCalibrationToFactory` / `setCalibrationFlowMultiplier`
    (`:201,203,342`), which exist (`state/app.svelte.ts:1469,1484,1509`) and reach core. The
    pill lies about a hardware-write control.
  - **Fix:** delete `notImplemented` from those three rows; keep `needsConnection`.
  - **Verify:** with a DE1 connected, the three rows are active and apply calibration.

- [ ] **T2-02 `[P1]` Add `visualizerAutoUpload` UI control + fix stale comment.**
  - **Problem:** the pref is read at upload time (`history/shot-persistence.ts:350`) but has
    no UI anywhere, and `settings/store.svelte.ts:152` comments it "not currently read
    anywhere" (false). Users can't disable auto-upload.
  - **Fix:** add an "Auto-upload finished shots" toggle to
    `components/settings/sections/SharingSection.svelte`; correct the comment.

- [ ] **T2-03 `[P1]` Wire Android QuickControls steam/water/flush steppers (core setters exist).**
  - **Problem:** steam (time/flow/temp), hot-water (temp/volume), flush (time/temp) steppers
    write only `remember` state (`ui/screens/QuickControlsSheet.kt:118-127`, set at
    `:257,270,283`); reset on close, change nothing — yet core exposes the setters on both
    bindings (`set_steam_flow`, `set_steam_hotwater_settings`, `set_flush_temp/timeout/
    flow_rate`, `set_hot_water_idle_temp`).
  - **Fix:** add `onChange` callbacks → VM methods backed by those FFI setters; or, if the
    per-mode param store isn't ready, gate the steppers behind a visible "Not implemented"
    affordance (see T2-04) rather than letting them silently no-op.

- [ ] **T2-04 `[P2]` Resolve Android grind/preinf dead-ends and unconsumed flush/purge prefs.**
  - **Problem:** Grind stepper (`QuickControlsSheet.kt:115`, local `:215`) and Pre-infuse
    stepper (`:117`, local `:244`) write only local state. `setPreFlush`/`setSteamPurge`
    **persist** to AppPrefs but no shot/steam sequence reads them
    (`MainViewModel.kt:1618,1624`); Settings pills them, but the Brew/QuickControls copies
    (`BrewScreen.kt:323-324`) don't, so they read as functional there.
  - **Fix:** implement the pre-shot-flush / post-steam-purge consumers, OR add the "Not
    implemented yet" affordance to the Brew/QuickControls copies for parity.

- [ ] **T2-05 `[P1]` Fix Android phone wiring regressions vs tablet.**
  - **Problem (4 distinct):**
    - Terms / Privacy rows have empty `{}` control lambda (`ui/phone/PhoneSettingsScreen.kt:915-916`)
      where tablet renders a readout (`SettingsScreen.kt:889-890`).
    - "All" beans filter returns `else -> true` → shows archived
      (`PhoneBeansScreen.kt:88`) vs tablet `b.archivedAt == null` (`BeansScreen.kt:141`).
    - Restock stepper steps 1.0g / max 5000 (`PhoneBeanEditScreen.kt:262,304`) vs tablet
      5.0g / max 2000 (`BeanEditScreen.kt:311`).
    - Steam & Tank brew tiles pass `ok = false` unconditionally
      (`PhoneBrewScreen.kt:711`) so they never show ready tint.
  - **Fix:** align each phone behavior to the tablet's (or to the more-correct of the two);
    derive ok-state for the Steam/Tank tiles or drop the param.

- [ ] **T2-06 `[P3]` Refresh stale "UI-only / TODO wire" doc-comment headers (web).**
  - **Problem:** headers claim controls are unwired when they now are:
    `components/brew/QuickSheet.svelte:17-19`, `BrewDashboard.svelte:20,375`,
    `routes/scale/+page.svelte:35-37`. No matching inline TODOs exist anymore.
  - **Fix:** update the headers to shipped reality.

> Not-action (intentional, correctly pilled): web water chemistry / density / screensaver /
> firmware-update (#55); Android units / water source / calibration toggles that lack a
> store seam (overlap with T1-08 and T3-05). Leave their honest "Not implemented yet" pills.

---

## Theme 5 — Core-internal (Rust)

- [ ] **T5-01 `[P2]` Close non-domain FFI parity gaps.**
  - **Problem:** `write_calibration`, `reset_calibration_to_factory`
    (`de1-wasm:1200-1212`) and `reset_machine_defaults` (`de1-wasm:1646`) are WASM-only —
    blockers the moment Android surfaces calibration-write / factory-reset controls.
  - **Fix:** mirror in `de1-ffi::CremaBridge` (thin wrappers identical to the read variants).

- [ ] **T5-02 `[P2]` Use the `f64_to_ms` helper everywhere instead of open-coding the cast.**
  - **Problem:** the finite-check `f64 → i64` ms cast is open-coded at
    `de1-wasm:340-357 (signature_for_shot)`, `:751-756 (export_beanconqueror)`,
    `:775-779 (export_crema_jsonl)`, `:839-840 (maintenance_readout)` while `f64_to_ms`
    (`:626`) is used correctly elsewhere — divergence risk.
  - **Fix:** route all sites through `f64_to_ms`.

- [ ] **T5-03 `[P2]` Align scale-command receivers `&mut self` → `&self` in WASM.**
  - **Problem:** `tare_scale` (`de1-wasm:1216`) and `set_scale_volume/standby/
    flow_smoothing/anti_mistouch/mode/auto_stop` take `&mut self` in WASM but `&self` in FFI;
    none mutate `CremaBridge` state.
  - **Fix:** change to `&self` to match FFI and correctness semantics.

- [ ] **T5-04 `[P3]` Make `set_line_frequency_override` reject non-{0,50,60} instead of truncating.**
  - **Problem:** `hz as i32` (`de1-wasm:1817`, `de1-ffi:1301`) silently accepts 50.9→"50"
    despite the doc saying only 0/50/60.
  - **Fix:** `hz.round() as i32`, or match `hz == 0.0 || 50.0 || 60.0` exactly.

- [ ] **T5-05 `[P3]` Replace `u64::MAX as f32` clamp ceiling in `set_espresso_warmup_timeout`.**
  - **Problem:** `de1-wasm:1194` / `de1-ffi:1194` clamp to `u64::MAX as f32` (precision loss)
    under a blanket clippy allow.
  - **Fix:** a named domain ceiling constant (e.g. 1h in ms) cast once.

- [ ] **T5-06 `[P3]` Add `RoastBand::from_wire_str` instead of inline match in `roast_freshness`.**
  - **Problem:** `de1-wasm:883-901` pattern-matches band wire strings inline rather than
    reusing the typed enum — fragile if bands change.
  - **Fix:** add a `from_wire_str(&str) -> Option<RoastBand>` to `de1-domain::bean`, call it.

- [ ] **T5-07 `[P3]` Document or remove stranded `brand::mark_svg()` / `mark_maskable_svg()`.**
  - **Problem:** `core/de1-domain/src/brand.rs:27-40` are `pub` but called by neither bridge;
    assets are pre-generated by `brand/generate_mark.py`.
  - **Fix:** add a `// build-tool only — assets pre-generated` module note, or expose via WASM
    if runtime generation is wanted.

- [ ] **T5-08 `[P3]` Avoid per-call alloc in `core_version`.**
  - **Problem:** `env!("CARGO_PKG_VERSION").to_string()` (`de1-wasm:619`, `de1-ffi:466`)
    allocates each call.
  - **Fix:** return the `&'static str` internally; `.to_owned()` only at the bridge boundary.

---

## Theme 4 — Intra-shell consolidation

### Android — phone ↔ tablet duplication (biggest cleanup)

- [ ] **T4-01 `[P2]` Collapse the three parallel settings-row systems.**
  - **Problem:** shared `SettingsRow` (`ui/phone/components/CremaPhoneComponents.kt:408`) is
    **dead** (referenced only in a comment, `:53`); phone defines private `PRow/PPill/PSelect/
    PStepper/PMono/PStatusDot` (`PhoneSettingsScreen.kt:924-1019`) near-duplicating tablet's
    private `SetRow/SetPill/SetSelect/SetStepper/MonoReadout/StatusDot`
    (`SettingsScreen.kt:945-1041`). Differences are only padding + a FlowRow pill wrap.
  - **Fix:** one parameterized row set (pill/connection semantics shared); delete the dead
    `SettingsRow` and both private copies.

- [ ] **T4-02 `[P2]` Extract `SettingsConfirmDialogs(...)` (copied verbatim).**
  - **Problem:** the staged-confirm block (7 `CremaConfirmDialog` bodies + SAF export launcher
    + all `pending*`/`confirm*` state) is copied verbatim:
    `PhoneSettingsScreen.kt:89-179` ≡ `SettingsScreen.kt:106-225`.

- [ ] **T4-03 `[P2]` Hoist per-domain filter/sort + brew-fallback helpers.**
  - **Problem:** duplicated predicates: beans (`PhoneBeansScreen.kt:75-99` ≡
    `BeansScreen.kt:126-152`), history (`PhoneHistoryScreen.kt:99-122` ≡
    `HistoryScreen.kt:146-168`), profiles (`PhoneProfilesScreen.kt:62-83` ≡
    `ProfilesScreen.kt:109-134`); and the brew-param fallback `?: 18.0/36/93` in 5 places
    (`PhoneBrewScreen.kt:439-440,657-658`, `PhoneBrewSheets.kt:52-54`,
    `QuickControlsSheet.kt:107-109`, `PhoneScaleScreen.kt:224-229`).
  - **Fix:** `filterAndSortBeans()`, `beanFilterCounts()`, extend the shared `historySortKeys`
    pattern, and a `MainUiState.effectiveBrew(active)`. (Catches the T2-05 "All"-filter and
    T1-01 ratio drifts at the source.)

- [ ] **T4-04 `[P3]` Reconcile low-tank threshold (phone 20f vs tablet 5f).**
  - **Problem:** `LOW_TANK_MM_PHONE = 20f` (`PhoneSettingsScreen.kt:487`) vs
    `LOW_TANK_MM = 5f` (`SettingsScreen.kt:82`) — refill warning fires at different levels.

- [ ] **T4-05 `[P2]` Hoist maintenance burn-down math.**
  - **Problem:** filter %, descale L/L, clean h/h + note strings duplicated
    `PhoneSettingsScreen.kt:533-551` vs `SettingsScreen.kt:434-477`.
  - **Fix:** a pure helper on the maintenance readout model.

- [ ] **T4-06 `[P2]` Extract `applyBeanEdits(bean, draft)` + share bag presets.**
  - **Problem:** bean editor `save{}` lambda field-for-field identical
    `PhoneBeanEditScreen.kt:92-140` ≡ `BeanEditScreen.kt:140-194`; `BAG_PRESETS` (`:39`)
    duplicates `BE_BAG_PRESETS`.

- [ ] **T4-07 `[P2]` Add `SegmentEdit.targetUnit()/limiterUnit()/exitUnit()`.**
  - **Problem:** `isPressure → "bar"/"ml/s"` mapping reimplemented 5+ sites:
    `PhoneProfileEditScreen.kt:288-296,354-355,431,439`, `ProfileEditScreen.kt:428,436,454,466`.
  - **Fix:** also promote `ProfileSegment.toEdit()` (`ProfileEditScreen.kt:514`, currently
    `private`) to the `profiles` package so phone stops inlining it (`PhoneProfileEditScreen.kt:85-104`).

- [ ] **T4-08 `[P2]` Share scale metadata + capability body.**
  - **Problem:** `scaleMeta(ui)` verbatim copy (`PhoneScaleScreen.kt:45-50` ≡
    `ScaleScreen.kt:74-79`); capability-gated settings body reimplemented
    (`PhoneScaleScreen.kt:172-314` vs `ScaleScreen.kt:291-382`) — phone even adds a wired
    display-mode row tablet drops (`:265-273`).

- [ ] **T4-09 `[P2]` Hoist the multi-channel spark chart.**
  - **Problem:** `PhoneSpark` (`PhoneHistoryScreen.kt:419-448`) functionally identical to
    `SparkChart` (`HistoryScreen.kt:528-563`). Hoist with stroke/size params.

### Android — shared component library (good, under-used)

- [ ] **T4-10 `[P2]` Route all steppers through `CremaStepper` (add a compact variant).**
  - **Problem:** ≥3 reimplementations — `EditStepper`/`StepperBox`
    (`ProfileEditScreen.kt:602,790`), `SetStepper`/`StepBtn` (`SettingsScreen.kt:1011,1022`),
    `QcStepper`/`QcStepBtn` (`QuickControlsSheet.kt:367,422`) — despite shared `CremaStepper`
    (`ui/components/CremaComponents.kt:1225`). Plus phone `PStepper`/`EdStepper`.
  - **Fix:** add a compact variant to `CremaStepper`, route all callers through it. Consider
    press-and-hold repeat (no shell has it today — see T3, cross-shell UX).

- [ ] **T4-11 `[P2]` Extract `CremaStarRating(value, onChange, sizeDp, readOnly)`.**
  - **Problem:** near-identical 5-star rows: `BeanEditScreen.kt:529`, `HistoryScreen.kt:724`,
    + read-only inline in `BeansScreen.kt:434`.

- [ ] **T4-12 `[P2]` Promote one freshness color/label helper.** (folds into T1-02)
  - **Problem:** `freshnessColor()` byte-identical in `BeansScreen.kt:528` and
    `BrewScreen.kt:1498` (comment admits the copy); phone uses different thresholds/colors.
  - **Fix:** one `freshnessBucket(frozen, days, band)` in `coffee.crema.beans`, fed by core's
    band-aware verdict (T1-02).

- [ ] **T4-13 `[P3]` Extract `CremaEmptyState(message, action?)`.**
  - **Problem:** centered empty-state hand-rolled 4× (`BeansScreen.kt:280,314`,
    `HistoryScreen.kt:228`, `ScaleScreen.kt:312`) and copy-pasted phone↔tablet for history.

- [ ] **T4-14 `[P2]` Route phone cards through `CremaCardSpec`.**
  - **Problem:** phone uses raw `Surface` with its own literals — `PhoneProfileCard` radius
    18dp (`PhoneProfilesScreen.kt:200`), phone beans card radius 18dp
    (`PhoneBeansScreen.kt:343`) — vs tablet `CremaCardSpec` 16dp
    (`CremaComponents.kt:456`). Silent corner-radius drift.
  - **Fix:** consume `CremaCardSpec` (with a single phone override if 18dp is intentional).

### Web (good, minor)

- [ ] **T4-15 `[P2]` Extract `useVisualizerConnection()` rune helper.**
  - **Problem:** the connection gate (`$state(false)` + `onMount` seed +
    `tokens.onConnectionChange`) is copy-pasted 3×: `components/beans/BeanDeleteSplit.svelte:42-51`,
    `RoasterDeleteSplit.svelte:43-52`, `settings/sections/SharingSection.svelte:61-66`.

- [ ] **T4-16 `[P2]` Share `bestEffortRemoteDelete(ids)`.**
  - **Problem:** free-tier-skip + `appCtx().services` guard + `console.warn` shape duplicated
    `BeanDeleteSplit.svelte:63-72` / `RoasterDeleteSplit.svelte:72-84`.

- [ ] **T4-17 `[P3]` Factor a shared `useStepper` numeric core.**
  - **Problem:** `components/brew/QuickStepper.svelte` and `settings/StStepper.svelte` are two
    implementations sharing the clamp + canonical↔display + commit logic under different
    chrome. Keep the two presentational shells; share the numeric core.

---

## Theme 3 — Design consistency across shells

> Several P1s here are the *presentation* half of Theme-1 items; cross-referenced so they're
> fixed together, not twice.

- [ ] **T3-01 `[P1]` One brew-ratio format @ 1 decimal everywhere.** (with T1-01)
  - **Problem:** web canonical `formatRatio` = 1 decimal, but Brew dashboard bypasses it with
    `.toFixed(2)` (`BrewDashboard.svelte:464,1251`, `YieldRatioStepper.svelte:32`); Android
    tablet `%.2f`, phone `%.1f`, tablet profile card `%.1f`.
  - **Fix:** route the web Brew dashboard through `formatRatio`; one Kotlin formatter (T1-01).

- [ ] **T3-02 `[P1]` Unify bean-freshness label + threshold + color.** (with T1-02)
  - **Problem:** web "In window/Fading/Stale" (`BeanDrawer.svelte:67-75`), tablet "Nd off
    roast" + hex bands (`BeansScreen.kt:423,528-535`), phone "Nd · resting/past peak" with
    different thresholds + telemetry-palette colors (`PhoneBeansScreen.kt:412-417`).
  - **Fix:** core-owned verdict (T1-02) → one label vocabulary + one color per band, all shells.

- [ ] **T3-03 `[P1]` Drop the phone roast→color map.**
  - **Problem:** phone `RoastPill` hardcodes per-band browns (`PhoneProfilesScreen.kt:338-340`:
    light `#E8C088` / medium `#D89A63` / dark `#BA8A66`); web + tablet intentionally use
    copper-wash only (`ProfilesScreen.kt:386-400`). Same Light bean = copper pill (web/tablet)
    vs tan pill (phone).
  - **Fix:** use the copper-wash convention on phone; also reconcile pill radius (phone 7dp
    vs tablet 999dp).

- [ ] **T3-04 `[P2]` One relative-time vocabulary per shell.**
  - **Problem:** five schemes — web ShotRow "min/hour/day/wk" (`ShotRow.svelte:61-71`), web
    Profiles "m/h/d/w/mo/y" (`profiles/model.ts:230-250`), tablet `compactAgo` "Nm/Nh/Nd ago"
    (`HistoryScreen.kt:507-515`), phone day-buckets "Today/Yesterday/This week/Earlier"
    (`PhoneHistoryScreen.kt:288-293`). A 2h-old shot reads differently on each.
  - **Fix:** one relative-time helper per shell; align the bucketing rule across both Android
    form factors.

- [ ] **T3-05 `[P2]` Implement or hide Android °C/°F and g/oz unit toggles.** (relates to T1 units export)
  - **Problem:** Android is Celsius/grams hardcoded; the Settings unit toggles are
    `notImplemented` on both tablet (`SettingsScreen.kt:547-552`) and phone
    (`PhoneSettingsScreen.kt:615-620`) — flipping does nothing. Weight also spaces
    differently ("18.0 g" tablet vs "18g" phone), temp decimals/glyph vary.
  - **Fix:** back a SettingsStore with units, route display through the core unit conversions
    (export via UniFFI); or hide the toggle until then. Standardize "18.0 g" / "93.0 °C".

- [ ] **T3-06 `[P2]` Reconcile `Copper300` token (web `#E0A375` vs Android `#E8A876`).**
  - **Problem:** `web/src/styles/tokens.css:54` vs `ui/theme/Color.kt:20`; the only copper stop
    that drifted (400/500/600/700 match). Used as accent tint.
  - **Fix:** pick one; align Android to `#E0A375` since 500-700 already match web.

- [ ] **T3-07 `[P3]` Reconcile "crema foam" surface (web `#E8D9BC` vs Android `#E5D9C3`).**
  - **Problem:** `tokens.css:41` vs `ui/theme/Color.kt:120` (`surfaceContainerHighest`,
    comment claims parity).

- [ ] **T3-08 `[P3]` Reconcile sheet radius (web `--radius-xl: 24px` vs Android `extraLarge 28dp`).**
  - **Problem:** `tokens.css:220` vs `ui/theme/Shape.kt:26`. Visible bottom-sheet corner delta.

- [ ] **T3-09 `[P3]` Standardize weight/temp formatting on Android.**
  - **Problem:** weight "18.0 g" (tablet) vs "18g"/"%.0fg" (phone cards,
    `PhoneProfilesScreen.kt:280`); temp `%.0f` on cards vs `%.1f` in brew readout; degree glyph
    "°C" vs bare "°". Move Android freshness/telemetry hex into the theme (a
    `CremaFreshnessColors` local like `CremaTelemetryColors`).

- [ ] **T3-10 `[P3]` Title-case the web ProfileCard roast chip.**
  - **Problem:** `ProfileCard.svelte:136` shows raw lowercase "light/medium/dark" while every
    other surface title-cases.

- [ ] **T3-11 `[P3]` Pick a canonical History stats set (tablet vs phone differ).**
  - **Problem:** tablet shows Avg yield / Avg time / Avg rating (`HistoryScreen.kt:391-393`);
    phone shows Today / Avg ratio / Avg rating (`PhoneHistoryScreen.kt:310-312`).

- [ ] **T3-12 `[P3]` Casing + empty-state type-scale nits.**
  - **Problem:** "Frozen" (tablet) vs "frozen" (phone, `PhoneBrewScreen.kt:203`); Scale
    empty-state `titleLarge` (tablet `ScaleScreen.kt:281`) vs `titleSmall` (phone
    `PhoneScaleScreen.kt:326`). Both fold into the shared freshness/empty-state helpers
    (T4-12, T4-13).

---

## Cross-references & sequencing

1. **Quick wins (hours):** T2-01, T1-04, T3-06/07/08, T2-06, T3-03, T3-10.
2. **Core export pass (1–2 days):** T1-01, T1-02, T1-03, T1-07, T5-01 — lands the
   correctness fixes (freshness, profile bounds) and kills the ratio drift. Then T1-06,
   T1-08, T1-09, T1-10.
3. **Web binding hygiene:** T1-05 (+ CI check).
4. **Android consolidation (2–3 days):** T4-01..T4-14 — many auto-resolve T3 nits.
5. **Feature gaps:** T2-03, T2-04, T2-05, T3-05, T1-11.
6. **Core polish:** T5-02..T5-08.

Items that fix two findings at once: T1-01↔T3-01, T1-02↔T3-02↔T4-12, T1-03 (bounds),
T1-08↔T3-05 (units), T4-03 (catches T2-05 "All"-filter + ratio drift at source).

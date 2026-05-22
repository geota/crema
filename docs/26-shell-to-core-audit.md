# Shell → Core Audit — what to push down

**Status:** read-only audit, 2026-05-22.
**Scope:** walked `web/src/lib/` to find logic that should live in the Rust core
(`core/`) so the planned Android shell — and any future shell — reuses it instead
of re-implementing it. Excluded: BLE transport, persistence mechanics, Svelte
runes, UI components, Visualizer upload, the per-user unit *preference* itself.

Conventions: every section gives a precise shell location, the one-line case for
core ownership, S/M/L effort (S = pure helper + bindgen export; M = new
domain type or facade method; L = touches existing FFI surface), and a L/M/H
risk band. Recommendation is one of **push** / **consider** / **keep**.

See `docs/13-deferred-refactors.md` for tasks already tracked there
(uPlot wrapper helpers, scale device-agnostic abstraction, button system) — not
re-flagged here.

---

## 1. Unit-conversion math (bar↔psi, °C↔°F, g↔oz, mL↔fl oz)

- **Where (shell):** `web/src/lib/settings/format.ts:42-105` — `convertWeight` /
  `convertTemp` / `convertPressure` / `convertVolume` and their `formatX`
  wrappers. Each is a one-line constant times the canonical SI-ish value.
- **Why it should live in core:** Android will reproduce these identical
  constants the moment it gains a unit picker; today it has none, so any
  duplication is locked in the moment the second shell ships. The math is
  domain-pure (no UI, no I/O), the constants are stable contracts (1 oz =
  28.3495 g, 1 bar = 14.5038 psi, 1 fl oz = 29.5735 mL), and "two implementations
  drift" is the canonical multi-shell hazard. Display formatting (`toFixed(1)`,
  the `—` dash, the unit-label string) stays in the shell — only the **numeric
  conversion** moves.
- **Effort:** S — four pure functions taking SI value + target-unit enum,
  returning `f32`. Bindgen-exposed via `typeshare` enums for `WeightUnit` /
  `TempUnit` / `PressureUnit` / `VolumeUnit`.
- **Risk:** L — pure functions, no state, exhaustively round-trip-testable.
- **Recommendation:** **push**. Cheapest win in the audit; saves a full
  duplicated module on Android day-one.

## 2. Tank-mm → mL lookup table (DE1 water tank geometry)

- **Where (shell):** `web/src/lib/state/ui-state.svelte.ts:483-510` — the
  68-entry `TANK_MM_TO_ML` constant + `waterTankMl()`. Ported verbatim from
  legacy `de1plus/vars.tcl`.
- **Why it should live in core:** this is **DE1 device geometry**, not UI. The
  DE1 protocol only reports tank depth in mm; converting to mL is identical
  domain work for every shell. Android needs it for the same "tank: 1.4 L"
  readout the web shows. Keeping the table in Rust also lets the core surface
  derived events directly (e.g. an `Event::WaterLevel` could carry both `mm` and
  `ml` so shells never re-derive). Sister function `waterRefillSoon`
  (`ui-state.svelte.ts:524-530`) is also pure DE1-domain math, push with it.
- **Effort:** S — one `&[u32; 68]` table and two `pub fn`s in `de1-domain`.
  Optionally extend `Event::WaterLevel` to include the derived mL.
- **Risk:** L — the table is verbatim from legacy; no behavioural change.
- **Recommendation:** **push**.

## 3. Puck-resistance derivation (`P / flow²`)

- **Where (shell):** `web/src/lib/state/ui-state.svelte.ts:586-602` —
  `puckResistance(pressure, flow)`, the de1app/DSx R4 metric. Called from
  `applyEvent` for every `Telemetry` sample (line 697); also re-computed inside
  `web/src/lib/history/store.svelte.ts:236` when importing a legacy log.
- **Why it should live in core:** this is a **canonical espresso-domain
  derivation** (matches reaprime, DSx, the legacy app). Two shells already need
  it (web computes it twice; Android will need it for any puck-feedback UI).
  The MIN_FLOW guard (0.2 mL/s) is part of the contract — it must not drift.
  The natural home is alongside `FlowEstimator` in `de1-domain`, or as an extra
  field on `Event::Telemetry` so shells never re-compute.
- **Effort:** S–M — `pub fn puck_resistance(p, flow) -> Option<f32>` in
  `de1-domain` is S; adding `resistance: Option<f32>` to `Event::Telemetry`
  (cleaner, removes a re-derive in two places) is M and touches the FFI
  envelope.
- **Risk:** L (helper) / M (envelope change — but trivial extra field on an
  already-`#[non_exhaustive]` event).
- **Recommendation:** **push** — adding the field to `Event::Telemetry` is
  preferred. The shell stops branching on `flow > 0.2`; that policy is the
  core's.

## 4. Brew-ratio label (`1:N` from yield ÷ dose)

- **Where (shell):** `web/src/lib/profiles/model.ts:174-177` (`ratioLabel` on a
  profile) and `web/src/lib/history/model.ts:97-105` (`ratioLabel` on a stored
  shot). The two have subtly different fallback policy: profile returns `1:—`
  on `dose ≤ 0`; history falls back to a hard-coded 18 g default. The label
  formatting (`.toFixed(1)`) is duplicated.
- **Why it should live in core:** the core already exposes
  `ShotMetadata::brew_ratio(&self) -> Option<f32>`
  (`core/de1-domain/src/history.rs:57-63`). Web doesn't use it. The shell
  re-implements ratio computation against its own `StoredShot` shape and
  against `CremaProfile`. A small `pub fn brew_ratio(dose, yield) -> Option<f32>`
  in `de1-domain` would unify both call sites; the **label string** stays in
  the shell (locale, font, dash glyph).
- **Effort:** S — extract one helper, route both shell `ratioLabel`s through it.
- **Risk:** L — pure float math; the existing `brew_ratio` test already covers it.
- **Recommendation:** **push** the *math*, **keep** the label string in the
  shell. (The history fallback to 18 g is shell policy and stays in the shell.)

## 5. Days-off-roast + roast-band + freshness classification

- **Where (shell):** `web/src/lib/bean/model.ts:54-137` —
  `roastBand(level)` (1..10 → light/medium/dark), `daysOffRoast(roastedOn)`,
  `roastFreshness(band, days)`, and the per-band `REST_WINDOW` constants
  (`light: 10–24 days best`, etc.).
- **Why it should live in core:** the 1..10 scale + band thresholds are a
  **Visualizer-contract** fact (the same scale the v2 `.shot.json` `bean`
  block carries). The day-count is calendar math, but the band thresholds and
  the rest-window verdicts are espresso-domain knowledge — every shell will
  classify a bean identically. Today the web shell ships this; Android would
  re-write it.
- **Effort:** M — needs a `Bean`/`ShotBean` domain type (the core has
  `ShotMetadata::beans: Option<String>` today — a flat string — which is
  weaker than the shell's typed bean and weaker than what Visualizer ships).
  Adding `BeanContext { roaster, type, roasted_on, roast_level }` to
  `de1-domain` opens a clean home for the classifier helpers.
- **Risk:** L (helpers in isolation); M if the typed bean replaces
  `ShotMetadata.beans` because that field is in `STORED_SHOT_FORMAT_VERSION=2`.
  A v3 break is fine per the project's "break the contract, I'm the only
  consumer" stance.
- **Recommendation:** **consider** — push the pure classifier
  (`roast_band`, `roast_freshness`) and `days_off_roast` as S helpers
  immediately; defer the typed `BeanContext` until Android starts modelling
  beans (otherwise speculative).

## 6. Community-v2 `.shot.json` export

- **Where (shell):** `web/src/lib/history/v2-export.ts:1-186` — 155 lines that
  rebuild the v2 `.shot.json` document from a `StoredShot`, with all its
  parallel-series + nested-meta + bean / shot / profile sub-shapes.
- **Why it should live in core:** the core **already** owns:
  - `de1_domain::history_import::import_v2_json_shot` — the v2 *parser*.
  - `de1_domain::profile_import::export_v2_json` — the v2 *profile* writer.
  - `de1_domain::StoredShot::to_json` — Crema's own pretty JSON shape.
  ...but **no** v2 `.shot.json` writer. The asymmetry is the bug. The wire
  contract (key names, parallel-series shape, the `enjoyment 0..100` mapping
  from a 1..5 rating) is **DE1-community domain knowledge** — the same body of
  knowledge `import_v2_json_shot` already encodes on the parse side, just in
  reverse. Android will need this export the day it gains "share shot" or
  "upload to Visualizer."
- **Effort:** M — write `pub fn export_v2_json_shot(shot: &StoredShot) -> String`
  in `core/de1-domain/src/history_import.rs`, symmetric with the import. The
  shell's `web/src/lib/history/v2-export.ts` collapses to a one-liner that
  feeds its `StoredShot` shape through the bindings.
- **Risk:** L–M — the round-trip with `import_v2_json_shot` becomes a free
  test. Risk is mismatched defaults (the shell stamps `app_name: 'crema'`,
  bean / grinder placeholders; pick canonical values in the core).
- **Recommendation:** **push**. Symmetry + free round-trip tests + identical
  rationale to the existing v2 *profile* export.

## 7. Days-off-roast + relative-time labels

- **Where (shell):** `web/src/lib/bean/model.ts:80-95` (days-off-roast, calendar
  UTC math) and `web/src/lib/profiles/model.ts:187-207` (`relativeLastUsed` —
  the `just now / 3h ago / 2d ago / 2w ago / 3mo ago / 1y ago` ladder).
- **Why it should live in core:** the days-off-roast math (push #5) shares
  a sibling: turning a roast date into "5 days off roast" is the same
  calendar-day math every shell needs. The `relativeLastUsed` ladder is more
  shell-shaped (i18n / locale concerns will surface there).
- **Effort:** S (days-off-roast — pure date math) / L (relative-time —
  i18n-loaded).
- **Risk:** L / H respectively.
- **Recommendation:** **push** days-off-roast (covered by #5). **Keep**
  `relativeLastUsed` in the shell — every native shell has its own
  relative-time API (`DateTimeFormatter` on Android, etc.) that beats a
  reinvented ladder.

## 8. Bookoo firmware-version decode (`u16 → "M.m.p"`)

- **Where (shell):** `web/src/lib/state/ui-state.svelte.ts:534-541` —
  `formatFirmware(encoded)` decodes the Bookoo's `major*100 + minor*10 + patch`
  packing into a display string.
- **Why it should live in core:** this is **device wire-format decoding**, the
  one job the core already owns. The Bookoo packing is part of the protocol;
  it should not live next to a UI-state file. Decoding belongs alongside
  `bookoo::CommandResponse::Serial { firmware_version, .. }` in
  `de1-scale::bookoo` (or on `Event::ScaleConfig`, which is what surfaces it).
  Once the core emits `firmware_version: String` already-decoded, the web's
  helper deletes; Android day-one gets the same string.
- **Effort:** S — modify `bookoo` parser to emit decoded `(major, minor, patch)`
  or a pre-formatted string; update the `Event::ScaleConfig.firmware_version`
  type accordingly.
- **Risk:** L — the wire format is stable. Touches one Event field type
  (`u16` → `String`).
- **Recommendation:** **push**. Tiny but a clear layering improvement.

## 9. Roast classification from profile title/notes

- **Where (shell):** `web/src/lib/profiles/model.ts:336-400` —
  `ROAST_OVERRIDES` (per-title pinned answers), `ROAST_PHRASES` (regex ladder),
  `roastFromProfile()`, `isTeaProfile`, `isUtilityProfile`. Run once per
  built-in profile to seed the `roast: Roast | null` field of `CremaProfile`.
- **Why it should live in core:** the **input** is a core `Profile`
  (title + notes); the **output** classifies that profile against the legacy
  DE1 corpus. Android will need this verbatim the moment its profile library
  ships. Tea / utility detection (`startsWith('tea/')`, `startsWith('cleaning/')`)
  is content the core's `builtin_profiles()` corpus structure dictates — it's
  closer to the corpus than to a UI.
- **Effort:** M — port ~60 lines of regex + override map into
  `core/de1-domain/src/builtin.rs` or a new `builtin_classify.rs`. Returns a
  `RoastBand` enum (matches push #5's band).
- **Risk:** L — pure function over `Profile`; the existing built-in tests
  expand cleanly.
- **Recommendation:** **consider** — push when Android starts building a
  profile library; not before. (Speculative today; high quality of life when
  Android catches up.)

## 10. Telemetry-derived shot metrics (peak pressure / peak weight / final weight)

- **Where (shell):** `web/src/lib/history/store.svelte.ts:92-103` (live record
  path) and again `:169-178` (imported-shot path) — both iterate the buffered
  series to compute `peakWeight`, `finalWeight`, `peakPressure`, `peakTemp`.
  Also derived inline in `web/src/lib/state/ui-state.svelte.ts:763-770`
  (the `ShotCompleted` `completedShot` summary).
- **Why it should live in core:** the core already has `ShotRecord` and
  `ShotMetrics` (`de1-domain::shot`); the **derived metrics** belong as
  methods on `ShotRecord` (or the `ShotMonitor` could emit them in
  `Event::ShotCompleted`). Three call sites compute the same thing today —
  one is the live path, one is the import path, one is the in-memory snapshot
  — and Android will be the fourth. This is the single highest-duplication
  finding in the audit by raw line count.
- **Effort:** M — add `ShotRecord::metrics(&self) -> ShotMetrics { peak_weight,
  final_weight, peak_pressure, peak_temp }` (some of this exists in
  `ShotMetrics` already; verify field set). Best: extend
  `Event::ShotCompleted` to carry the metrics directly so the shell's
  `applyEvent` and `HistoryStore.record` both consume them ready-made.
- **Risk:** M — touches the `Event::ShotCompleted` payload (FFI envelope
  change). `#[non_exhaustive]` on the variant covers it; clients add fields
  without breaking.
- **Recommendation:** **push**. High duplication × multi-shell × the data
  the core already has at fingertips.

## 11. Profile / segment validation ranges (dose, yield, temp, time…)

- **Where (shell):** Hard-coded into the `<PeNumber min={…} max={…}>` /
  `<QStepper min max>` attributes:
  - `web/src/lib/components/profiles/ProfileEditor.svelte:391-457` — dose
    `5..30 g`, yield `10..80 g`, brew temp `80..100 °C`, max volume `0..1023`
    mL, preinfuse steps `0..segments.length`, tank temp `0..100 °C`.
  - `web/src/lib/components/profiles/SegmentRow.svelte:248-310` — segment
    target `0..12` bar/(mL/s), time `1..60 s`, temp `80..105 °C`, volume limit
    `0..1023 mL`.
  - `web/src/lib/profiles/curve.ts:260` — drag-time clamp `1..60 s`,
    target rounding to 0.1.
- **Why it should live in core:** profile ranges are part of the **DE1 contract
  + the espresso domain** (the 0..1023 mL limit is the wire protocol's 10-bit
  field; the 80..105 °C is the firmware's safe range). Today these magic
  numbers are scattered across multiple Svelte files; Android will need each
  one when its editor lands. The cleanest contract: a `pub const` module in
  `de1-domain::profile` (e.g. `pub const DOSE_RANGE_G: RangeInclusive<f32> =
  5.0..=30.0`) exposed as typeshared records, OR a `validate_profile(&Profile)
  -> Result<(), Vec<ValidationError>>` that both shells can call before upload.
- **Effort:** M — one Rust module of constants + an optional
  `validate_profile` aggregator. Two dozen line items moved.
- **Risk:** L — pure data + pure validator. The wire-protocol ranges are
  already known.
- **Recommendation:** **push**. Wire-protocol bounds (the 0..1023 volume
  limit, the firmware 80..105 °C band) are unambiguously core; the
  editor's UX bounds (dose `5..30`) are *editorial choices* and could stay
  in the shell — but unifying them avoids drift. Recommend: push the
  protocol-mandated bounds; document the editorial ones as `RECOMMENDED_*`
  consts the shell may relax.

## 12. AutoStop / brew-control wiring (today: brew-params is shell-only)

- **Where (shell):** `web/src/lib/components/brew/brew-params.svelte.ts:14-119`
  — `BrewParams` (dose, yield, brewTemp, preinf, steamTime/Flow, waterTemp/
  Volume, flushTime, stopOnWeight, autoTare). Every stepper is annotated
  `// TODO: wire to DE1 control` — so today this is **pure local UI state** with
  no machine driving.
- **Why it should live in core:** when brew-control becomes real, the
  `BrewParams` shape needs to round-trip through `CremaCore::arm_auto_stop`
  (which already takes `weight`, `volume`, `max_time`). Once that wiring lands,
  the shell's `BrewParams.stopOnWeight + .yield + .stopOnVolume + .volume`
  becomes a `StopTargets` dispatch.
- **Effort:** N/A today.
- **Risk:** N/A today.
- **Recommendation:** **keep in shell** for now (TODO-fence intact). Re-audit
  when the brew-control implementation lands — at that point most of
  `BrewParams` becomes the input to `CremaCore::arm_auto_stop` + a future
  `CremaCore::configure_brew(...)`.

## 13. Maintenance counters (filter %, descale L, backflush hours)

- **Where (shell):** `web/src/lib/maintenance/store.svelte.ts:1-195` — total
  litres counter, baselines, derived `MaintenanceReadout` (filterPercent,
  descaleSinceLitres, backflushSinceHours). The accumulation math
  (`flow × Δt`, capped at 1000 mL/sample) mirrors the legacy app's
  `de1_de1.tcl:570-628` integral; the accumulation is **fed** by the shell
  from `Event::Telemetry.group_flow` and a wall-clock Δt (see
  `web/src/lib/state/app.svelte.ts:278-287`).
- **Why it should live in core:** half-and-half. The core *already* integrates
  volume (`VolumeIntegrator` in `de1-domain::volume`) using the DE1's own
  `sample_time` (more accurate than the shell's wall-clock Δt — line-frequency
  locked). Today the maintenance store re-integrates with a noisier signal.
  Cleanest: the core emits a cumulative-litres counter (`Event::Telemetry`
  could carry it, or a new `Event::WaterAccumulated`), and the shell's
  maintenance store only owns the baselines + the user-set intervals
  (those are user policy and stay in the shell). The state-gate (only
  Espresso / HotWater / HotWaterRinse count — `app.svelte.ts:110-114`) is
  also domain knowledge and should move.
- **Effort:** M — add cumulative-litres counter to `CremaCore` (it already has
  `dispensed_volume_ml` per shot; "ever, across counted states" is one
  step further); emit it on `Telemetry` or a dedicated event. Shell shrinks
  to baselines + intervals.
- **Risk:** M — semantics differ from today (line-freq integration vs.
  wall-clock); regression possible if Δt clamp logic differs.
- **Recommendation:** **consider** — clear win in correctness (line-freq Δt
  is better) and parity (Android needs maintenance too), but worth deferring
  until the Android shell is far enough along to actually use it.

## 14. ISO-string + timestamp helpers (`shotFilename`, `completedAt`)

- **Where (shell):** `web/src/lib/history/model.ts:114-121` —
  `shotFilename(record)` formats `yyyymmddTHHMMSS.shot.json`.
- **Why it should live in core:** filename formatting is shell concern (locale,
  filesystem conventions). The **timestamp itself** is already in core
  (`StoredShot.recorded_at` Unix ms).
- **Recommendation:** **keep in shell**.

## 15. Bookoo capture-source name mapping (replay)

- **Where (shell):** `web/src/lib/replay/capture.ts:71-86` — `sourceFromName`
  maps `DE1_STATE` / `SCALE_FF12` / etc. to the web's `NotificationSource`
  enum. Comment explicitly notes this **already mirrors**
  `core/de1-app/examples/replay.rs::source_from_name`.
- **Why it should live in core:** it does — there's already a Rust copy in
  `examples/`. But that copy is an *example*, not exported. Promoting it to
  a `pub fn` on `Source` (or a typeshare-friendly conversion impl) lets both
  shells use one source-of-truth instead of mirroring it.
- **Effort:** S — promote the example function to a real associated function
  on `Source`. Wire `parseCapture` through `loadCore()`.
- **Risk:** L.
- **Recommendation:** **consider**. Low-priority de-duplication; the table is
  small and stable. Push when the capture format is touched for any other
  reason.

---

## Prioritized summary

### Top 3 wins (do these first)

1. **#1 — Unit conversion math (`format.ts`)**. Cheapest, highest-clarity.
   S/L; one bindgen export round. Saves all of `format.ts`'s arithmetic on
   Android. *No reason not to push immediately.*
2. **#10 — Telemetry-derived shot metrics**. Highest raw-duplication finding;
   three call sites in the web today + Android-future = four. Best done by
   extending `Event::ShotCompleted` (`#[non_exhaustive]` covers the FFI break).
   Removes the imperative re-iteration of `series` in
   `history/store.svelte.ts` and `ui-state.svelte.ts`.
3. **#6 — Community-v2 `.shot.json` export**. The asymmetry is the bug —
   the core owns import + the symmetric v2-profile export already. Adding
   `export_v2_json_shot` gives a free round-trip test and removes the
   shell's 155-line v2-shot module the moment Android needs it.

### Strong secondary wins

- **#2 — Tank-mm → mL lookup**. S/L, pure DE1 device geometry. Push now.
- **#3 — Puck-resistance derivation**. S/L if a helper; M if extended onto
  `Event::Telemetry` (recommended).
- **#4 — Brew-ratio math**. Tiny, but it unifies two existing shell call
  sites against an already-existing core function (`ShotMetadata::brew_ratio`)
  that the web shell doesn't currently use.
- **#11 — Profile validation ranges**. Push the wire-protocol-mandated bounds;
  leave editorial UX ranges in the shell.
- **#8 — Bookoo firmware-version decode**. Layering cleanup; one Event field
  type change.

### Defer (good ideas, not yet)

- **#5 / #9 — Bean / roast typed-classification + profile-roast inference.**
  Push the pure helpers (`roast_band`, `roast_freshness`, `days_off_roast`)
  now (S); defer the typed `BeanContext` and the `roastFromProfile` regex
  ladder until Android starts modelling beans / classifying its library —
  otherwise speculative.
- **#13 — Maintenance counters.** Correct direction (line-freq integration
  beats wall-clock), but the migration is M/M-risk. Defer until Android
  starts on maintenance UI.
- **#15 — Capture `sourceFromName`.** Small enough that pushing it isn't
  urgent; promote when the capture format is touched anyway.

### Do NOT push down

- **`relativeLastUsed`** (the "3h ago / 2d ago" ladder,
  `profiles/model.ts:187-207`) — every native shell has a superior built-in
  (`DateTimeFormatter`, `Intl.RelativeTimeFormat`). Pushing it would lock in
  a worse locale story.
- **`shotFilename`** — filesystem / locale concern; not domain.
- **`BrewParams` / `brew-params.svelte.ts`** — today it's TODO-fenced local
  UI state. Re-audit when brew-control wiring lands; *most of it then*
  becomes input to `CremaCore::arm_auto_stop` + a future
  `CremaCore::configure_brew`, but pushing the shape now is premature.
- **The unit *preference* itself** — `weightUnit` / `tempUnit` / `pressureUnit`
  / `volumeUnit` in `settings/store.svelte.ts:30-33` stays in the shell per
  the audit's stated scope. Only the conversion math moves (#1).
- **Roast / freshness colour tokens, `daisyUI` theme bits, the
  `ROAST_PILL_LEVEL` map** — visual concerns.

---

## A note on the existing `StoredShot` shape mismatch

The web's `StoredShot` (`web/src/lib/history/model.ts:54-89`) and the core's
`de1_domain::StoredShot` (`core/de1-domain/src/history.rs:68-82`) **are
distinct types** that the shell maps between manually (see
`history/store.svelte.ts:165-200`, `mapTimedSamples`, `beanFromImported`,
`durationToMs`). This dual-shape design was deliberate
(`docs/13-deferred-refactors.md` "Persisted format note") — but pushing #5
(typed bean), #10 (metrics on `Event::ShotCompleted`) and #6 (v2-shot
export) all narrow the gap and reduce the hand-mapping. If those land, a
follow-up audit could revisit whether the shell needs its own `StoredShot`
shape at all, or could persist the core's directly — saving the
`mapTimedSamples` + `beanFromImported` + `durationToMs` adapters.
